package com.sahahub.payment.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.domain.CashSession;
import com.sahahub.payment.domain.CashSessionRepository;
import com.sahahub.payment.domain.Expense;
import com.sahahub.payment.domain.ExpenseRepository;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.Money;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Günlük kasa ve giderler.
 * <p>
 * Kasada beklenen tutar = açılış parası + nakit tahsilat − nakit iade − nakit ters kayıt − kasadan gider.
 * Manuel POS ve havale kasadaki nakdi etkilemez; ekranda ayrıca gösterilir.
 */
@Service
public class CashService {

	/** Kasa ekranı modeli. */
	public record CashView(Long branchId, String branchName, OpenSession open, List<PaymentLine> todayPayments,
			BigDecimal todayCash, BigDecimal todayPos, BigDecimal todayTransfer, BigDecimal todayOnline,
			List<ExpenseLine> expenses, List<ClosedSession> history, boolean canManageExpenses, String currency) {
	}

	public record OpenSession(Long id, ZonedDateTime openedAt, BigDecimal openingFloat, BigDecimal cashMovements,
			BigDecimal cashExpenses, BigDecimal expected) {
	}

	public record ClosedSession(Long id, ZonedDateTime openedAt, ZonedDateTime closedAt, BigDecimal expected,
			BigDecimal counted, BigDecimal difference, String note) {
	}

	public record PaymentLine(ZonedDateTime at, Payment.Kind kind, Payment.Method method, BigDecimal amount,
			Long reservationId) {
	}

	public record ExpenseLine(Long id, ZonedDateTime at, Expense.Category category, BigDecimal amount,
			String description, boolean fromCash, boolean reversible) {
	}

	private final CashSessionRepository sessions;
	private final ExpenseRepository expenses;
	private final PaymentRepository payments;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final AuditService audit;
	private final Clock clock;

	public CashService(CashSessionRepository sessions, ExpenseRepository expenses, PaymentRepository payments,
			CatalogService catalog, AccessGuard guard, AuditService audit, Clock clock) {
		this.sessions = sessions;
		this.expenses = expenses;
		this.payments = payments;
		this.catalog = catalog;
		this.guard = guard;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public void open(AppUserPrincipal staff, Long branchId, BigDecimal openingFloat) {
		BranchContext bc = require(staff, branchId, Permission.CASH_MANAGE);
		if (sessions.findOpen(branchId).isPresent()) {
			throw new BusinessRuleException("Bu şubede zaten açık bir kasa var.");
		}
		try {
			CashSession s = sessions.saveAndFlush(new CashSession(branchId,
					openingFloat == null ? BigDecimal.ZERO : openingFloat, staff.id(), Instant.now(clock)));
			audit.record(staff.id(), bc.business().getId(), "CASH_OPENED", "CashSession", s.getId(),
					"float=" + s.getOpeningFloat());
		}
		catch (DataIntegrityViolationException ex) {
			// İki personel aynı anda kasa açarsa tekil indeks ikincisini reddeder
			throw new BusinessRuleException("Bu şubede zaten açık bir kasa var.");
		}
	}

	@Transactional
	public CashSession close(AppUserPrincipal staff, Long branchId, BigDecimal counted, String note) {
		BranchContext bc = require(staff, branchId, Permission.CASH_MANAGE);
		CashSession s = sessions.findOpenForUpdate(branchId)
			.orElseThrow(() -> new BusinessRuleException("Açık kasa yok."));
		BigDecimal expected = expected(s);
		s.close(expected, counted, note, staff.id(), Instant.now(clock));
		audit.record(staff.id(), bc.business().getId(), "CASH_CLOSED", "CashSession", s.getId(),
				"expected=" + expected + ", counted=" + counted + ", difference=" + s.difference());
		return s;
	}

	@Transactional
	public void addExpense(AppUserPrincipal staff, Long branchId, Expense.Category category, BigDecimal amount,
			String description, boolean fromCash) {
		BranchContext bc = require(staff, branchId, Permission.EXPENSE_MANAGE);
		Long sessionId = null;
		if (fromCash) {
			sessionId = sessions.findOpen(branchId)
				.orElseThrow(() -> new BusinessRuleException("Kasadan ödenen gider için önce kasayı açın."))
				.getId();
		}
		if (amount != null && amount.scale() > 2) {
			throw new BusinessRuleException("Tutar en fazla iki ondalık basamak içerebilir.");
		}
		Expense e = expenses.save(new Expense(bc.business().getId(), branchId, category, amount, description, sessionId,
				staff.id(), Instant.now(clock)));
		audit.record(staff.id(), bc.business().getId(), "EXPENSE_RECORDED", "Expense", e.getId(),
				category + ", " + amount);
	}

	@Transactional
	public void reverseExpense(AppUserPrincipal staff, Long expenseId) {
		Expense e = expenses.findById(expenseId).orElseThrow(() -> new NotFoundException("Gider"));
		BranchContext bc = require(staff, e.getBranchId(), Permission.EXPENSE_MANAGE);
		if (e.getReversalOf() != null || expenses.existsByReversalOf(e.getId())) {
			throw new BusinessRuleException("Bu gider zaten ters kayıtla düzeltilmiş.");
		}
		Long sessionId = null;
		if (e.isPaidFromCash()) {
			sessionId = sessions.findOpen(e.getBranchId())
				.orElseThrow(() -> new BusinessRuleException("Kasadan ödenen gideri düzeltmek için kasayı açın."))
				.getId();
		}
		try {
			Expense r = expenses.saveAndFlush(e.reversal(sessionId, staff.id(), Instant.now(clock)));
			audit.record(staff.id(), bc.business().getId(), "EXPENSE_REVERSED", "Expense", r.getId(),
					"of=" + e.getId());
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu gider zaten ters kayıtla düzeltilmiş.");
		}
	}

	@Transactional(readOnly = true)
	public CashView view(AppUserPrincipal staff, Long branchId) {
		BranchContext bc = require(staff, branchId, Permission.CASH_MANAGE);
		ZoneId zone = bc.branch().zone();
		Optional<CashSession> open = sessions.findOpen(branchId);
		OpenSession openView = open.map(s -> {
			BigDecimal moves = payments.netCashInSession(s.getId());
			BigDecimal exp = expenses.cashExpensesInSession(s.getId());
			return new OpenSession(s.getId(), s.getOpenedAt().atZone(zone), s.getOpeningFloat(), moves, exp,
					expected(s));
		}).orElse(null);

		LocalDate today = LocalDate.now(clock.withZone(zone));
		Instant from = today.atStartOfDay(zone).toInstant();
		Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
		List<Payment> todays = payments.findCompletedBetween(branchId, from, to);
		List<PaymentLine> lines = todays.stream()
			.map(p -> new PaymentLine(p.getCompletedAt().atZone(zone), p.getKind(), p.getMethod(), p.getAmount(),
					p.getReservationId()))
			.toList();

		List<ExpenseLine> expenseLines = expenses.findTop30ByBranchIdOrderByCreatedAtDesc(branchId)
			.stream()
			.map(e -> new ExpenseLine(e.getId(), e.getCreatedAt().atZone(zone), e.getCategory(), e.getAmount(),
					e.getDescription(), e.isPaidFromCash(),
					e.getReversalOf() == null && !expenses.existsByReversalOf(e.getId())))
			.toList();
		List<ClosedSession> history = sessions.findTop10ByBranchIdOrderByOpenedAtDesc(branchId)
			.stream()
			.filter(s -> !s.isOpen())
			.map(s -> new ClosedSession(s.getId(), s.getOpenedAt().atZone(zone), s.getClosedAt().atZone(zone),
					s.getExpectedAmount(), s.getCountedAmount(), s.difference(), s.getNote()))
			.toList();
		return new CashView(branchId, bc.branch().getName(), openView, lines, net(todays, Payment.Method.CASH),
				net(todays, Payment.Method.MANUAL_POS), net(todays, Payment.Method.BANK_TRANSFER),
				net(todays, Payment.Method.ONLINE_SIM), expenseLines, history,
				guard.can(staff, bc.business().getId(), branchId, Permission.EXPENSE_MANAGE), Money.DEFAULT_CURRENCY);
	}

	private BigDecimal expected(CashSession s) {
		return Money.round(s.getOpeningFloat()
			.add(payments.netCashInSession(s.getId()))
			.subtract(expenses.cashExpensesInSession(s.getId())));
	}

	private static BigDecimal net(List<Payment> list, Payment.Method method) {
		return list.stream()
			.filter(p -> p.getMethod() == method)
			.map(Payment::signedAmount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private BranchContext require(AppUserPrincipal staff, Long branchId, Permission permission) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(staff, bc.business().getId(), branchId, permission);
		return bc;
	}

}
