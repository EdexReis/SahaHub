package com.sahahub.payment.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.domain.CashSession;
import com.sahahub.payment.domain.CashSessionRepository;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.domain.PaymentSummary;
import com.sahahub.payment.provider.PaymentProvider;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Tahsilat, havale doğrulama, ters kayıt ve iade.
 * <p>
 * Eşzamanlılık: her işlem önce rezervasyon satırını kilitler (FOR UPDATE). Aynı rezervasyon üzerinde
 * aynı anda yapılan iki iade sırayla işlenir; ikincisi güncel "iade edilebilir" tutarı görür.
 * İdempotency: her isteğin anahtarı payment.idempotency_key (UNIQUE) olarak saklanır. Aynı anahtar
 * yeniden gelirse yeni hareket oluşmaz, mevcut hareket döner.
 */
@Service
public class PaymentService {

	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final CashSessionRepository cashSessions;
	private final PaymentProvider provider;
	private final AccessGuard guard;
	private final AuditService audit;
	private final Clock clock;

	public PaymentService(PaymentRepository payments, ReservationRepository reservations,
			CashSessionRepository cashSessions, PaymentProvider provider, AccessGuard guard, AuditService audit,
			Clock clock) {
		this.payments = payments;
		this.reservations = reservations;
		this.cashSessions = cashSessions;
		this.provider = provider;
		this.guard = guard;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ tahsilat

	/**
	 * Kasada nakit veya manuel POS ile alınan ödeme. Nakit için şubede açık kasa gerekir.
	 * Kalan borçtan fazlası alınamaz (fazla ödeme oluşmaz).
	 */
	@Transactional
	public Payment collect(AppUserPrincipal staff, String code, Payment.Method method, BigDecimal amount, String key,
			String note) {
		Optional<Payment> existing = payments.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (method != Payment.Method.CASH && method != Payment.Method.MANUAL_POS) {
			throw new BusinessRuleException("Kasadan yalnızca nakit veya manuel POS tahsilatı girilir.");
		}
		Reservation r = lockForStaff(staff, code, Permission.PAYMENT_COLLECT);
		if (r.getStatus() == ReservationStatus.CANCELLED || r.getStatus() == ReservationStatus.EXPIRED
				|| r.getStatus() == ReservationStatus.HELD) {
			throw new BusinessRuleException("Bu rezervasyon için tahsilat alınamaz (" + r.getStatus().label() + ").");
		}
		PaymentSummary s = PaymentQueries.summarize(r, payments.findByReservationIdOrderByIdAsc(r.getId()));
		requirePositive(amount);
		if (amount.compareTo(s.balance()) > 0) {
			throw new BusinessRuleException("Tutar kalan borçtan (" + s.balance() + " " + s.currency() + ") fazla olamaz.");
		}
		Long sessionId = method == Payment.Method.CASH ? openSession(r.getBranchId()).getId() : null;
		Payment p = save(Payment.collected(r.getBusinessId(), r.getBranchId(), r.getId(), method, amount,
				r.getCurrency(), key, sessionId, clean(note), staff.id(), Instant.now(clock)));
		audit.record(staff.id(), r.getBusinessId(), "PAYMENT_COLLECTED", "Payment", p.getId(),
				"reservation=" + code + ", method=" + method + ", amount=" + amount);
		return p;
	}

	// ------------------------------------------------------------------ havale

	/**
	 * Havale/EFT bildirimi: personel doğrulayana kadar PENDING kalır ve ödenene sayılmaz.
	 * Müşteri yalnızca kendi onaylı rezervasyonu için, personel izinle bildirebilir.
	 */
	@Transactional
	public Payment notifyTransfer(AppUserPrincipal user, String code, BigDecimal amount, String payerName,
			String key) {
		Optional<Payment> existing = payments.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return existing.get();
		}
		Reservation r = reservations.findByCodeForUpdate(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		boolean own = user.id().equals(r.getCustomerId());
		if (!own) {
			if (!guard.can(user, r.getBusinessId(), r.getBranchId(), Permission.PAYMENT_COLLECT)) {
				throw new NotFoundException("Rezervasyon");
			}
		}
		if (r.getStatus() != ReservationStatus.CONFIRMED) {
			throw new BusinessRuleException("Havale bildirimi yalnızca onaylı rezervasyon için yapılabilir.");
		}
		if (payerName == null || payerName.isBlank()) {
			throw new BusinessRuleException("Gönderen adını yazın.");
		}
		PaymentSummary s = PaymentQueries.summarize(r, payments.findByReservationIdOrderByIdAsc(r.getId()));
		requirePositive(amount);
		if (amount.compareTo(s.balance().subtract(s.pending())) > 0) {
			throw new BusinessRuleException("Tutar kalan borçtan fazla olamaz.");
		}
		return save(Payment.pendingCharge(r.getBusinessId(), r.getBranchId(), r.getId(), Payment.Method.BANK_TRANSFER,
				amount, r.getCurrency(), key, payerName.strip(), null, user.id(), Instant.now(clock)));
	}

	@Transactional
	public void verifyTransfer(AppUserPrincipal staff, Long paymentId, boolean accepted, String reason) {
		Payment p = payments.findById(paymentId)
			.filter(x -> x.getMethod() == Payment.Method.BANK_TRANSFER && x.getKind() == Payment.Kind.CHARGE)
			.orElseThrow(() -> new NotFoundException("Havale bildirimi"));
		guard.requireBranch(staff, p.getBusinessId(), p.getBranchId(), Permission.PAYMENT_COLLECT);
		reservations.findByIdForUpdate(p.getReservationId());
		if (!p.isPending()) {
			throw new BusinessRuleException("Bu bildirim zaten sonuçlandırılmış.");
		}
		Instant now = Instant.now(clock);
		if (accepted) {
			p.succeed(now);
		}
		else {
			if (reason == null || reason.isBlank()) {
				throw new BusinessRuleException("Reddetme gerekçesini yazın.");
			}
			p.fail(reason.strip(), now);
		}
		audit.record(staff.id(), p.getBusinessId(), accepted ? "TRANSFER_VERIFIED" : "TRANSFER_REJECTED", "Payment",
				p.getId(), reason);
	}

	// ------------------------------------------------------------------ ters kayıt

	/**
	 * Yanlış girilen nakit/POS tahsilatını düzeltir: tahsilat silinmez, tam tutarlı ters kaydı eklenir.
	 * Tahsilata iade yapılmışsa ters kayıt yapılamaz (kısmi tekil indeks de ikinci ters kaydı engeller).
	 */
	@Transactional
	public Payment reverse(AppUserPrincipal staff, String code, Long chargeId, String reason, String key) {
		Optional<Payment> existing = payments.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return existing.get();
		}
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("Düzeltme gerekçesini yazın.");
		}
		Reservation r = lockForStaff(staff, code, Permission.PAYMENT_COLLECT);
		Payment charge = chargeOf(r, chargeId);
		if (charge.getMethod() != Payment.Method.CASH && charge.getMethod() != Payment.Method.MANUAL_POS) {
			throw new BusinessRuleException("Yalnızca elle girilen nakit/POS tahsilatı ters çevrilebilir.");
		}
		if (!payments.findByRelatedPaymentIdOrderByIdAsc(charge.getId()).isEmpty()) {
			throw new BusinessRuleException("Bu tahsilata iade veya düzeltme yapılmış; ters kayıt yapılamaz.");
		}
		Long sessionId = charge.getMethod() == Payment.Method.CASH ? openSession(r.getBranchId()).getId() : null;
		Payment reversal = Payment.against(charge, Payment.Kind.REVERSAL, charge.getMethod(), charge.getAmount(), key,
				sessionId, reason.strip(), staff.id(), Instant.now(clock));
		reversal.succeed(Instant.now(clock));
		Payment saved = save(reversal);
		audit.record(staff.id(), r.getBusinessId(), "PAYMENT_REVERSED", "Payment", saved.getId(),
				"charge=" + charge.getId() + ", reason=" + reason.strip());
		return saved;
	}

	// ------------------------------------------------------------------ iade

	/** Personelin başlattığı iade. Nakit iade kasadan çıkar (açık kasa gerekir). */
	@Transactional
	public Payment refund(AppUserPrincipal staff, String code, Long chargeId, BigDecimal amount, String key,
			String note) {
		Optional<Payment> existing = payments.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return existing.get();
		}
		Reservation r = lockForStaff(staff, code, Permission.PAYMENT_REFUND);
		Payment charge = chargeOf(r, chargeId);
		Payment refund = doRefund(r, charge, amount, key, clean(note), staff.id());
		audit.record(staff.id(), r.getBusinessId(), "PAYMENT_REFUNDED", "Payment", refund.getId(),
				"charge=" + chargeId + ", amount=" + amount + ", status=" + refund.getStatus());
		return refund;
	}

	/**
	 * Sistemin kendiliğinden yaptığı tam iade (geç gelen ödeme, müşterinin süresi içinde iptali).
	 * Yalnızca çevrim içi ödemelere uygulanır. Anahtar sabit olduğu için iki kez tetiklense de tek iade olur.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<Payment> autoRefund(Long chargeId, String why) {
		String key = "auto-refund-" + chargeId;
		Optional<Payment> existing = payments.findByIdempotencyKey(key);
		if (existing.isPresent()) {
			return existing;
		}
		Payment charge = payments.findById(chargeId).orElseThrow();
		Reservation r = reservations.findByIdForUpdate(charge.getReservationId()).orElseThrow();
		if (charge.getMethod() != Payment.Method.ONLINE_SIM || !charge.isSucceeded()) {
			return Optional.empty();
		}
		BigDecimal refundable = PaymentQueries.refundableFor(charge, payments.findByReservationIdOrderByIdAsc(r.getId()));
		if (refundable.signum() == 0) {
			return Optional.empty();
		}
		Payment refund = doRefund(r, charge, refundable, key, why, null);
		audit.record(null, r.getBusinessId(), "PAYMENT_AUTO_REFUND", "Payment", refund.getId(),
				"charge=" + chargeId + ", status=" + refund.getStatus() + ", why=" + why);
		return Optional.of(refund);
	}

	private Payment doRefund(Reservation r, Payment charge, BigDecimal amount, String key, String note, Long userId) {
		requirePositive(amount);
		List<Payment> all = payments.findByReservationIdOrderByIdAsc(r.getId());
		BigDecimal refundable = PaymentQueries.refundableFor(charge, all);
		if (amount.compareTo(refundable) > 0) {
			throw new BusinessRuleException("Bu ödemeden en fazla " + refundable + " " + charge.getCurrency()
					+ " iade edilebilir.");
		}
		Instant now = Instant.now(clock);
		Long sessionId = charge.getMethod() == Payment.Method.CASH ? openSession(r.getBranchId()).getId() : null;
		Payment refund = save(Payment.against(charge, Payment.Kind.REFUND, charge.getMethod(), amount, key, sessionId,
				note, userId, now));
		if (charge.getMethod() == Payment.Method.ONLINE_SIM) {
			// Not: gerçek bir sağlayıcıda bu çağrı transaction dışında ve zaman aşımlı yapılmalı.
			// Simülasyon aynı veritabanında çalıştığı için burada senkron çağrılıyor.
			PaymentProvider.RefundOutcome outcome = provider.refund(charge.getProviderRef(), amount, key);
			if (outcome.succeeded()) {
				refund.succeed(now);
			}
			else {
				refund.fail(outcome.failureReason(), now);
			}
		}
		else {
			// Nakit, manuel POS ve havale iadesini personel kendisi yapar; kayıt anında başarılıdır.
			refund.succeed(now);
		}
		return refund;
	}

	// ------------------------------------------------------------------ yardımcılar

	private Payment save(Payment p) {
		try {
			return payments.saveAndFlush(p);
		}
		catch (DataIntegrityViolationException ex) {
			// Aynı anahtarla eşzamanlı iki istek: ikincisi tekil kısıta takılır. PostgreSQL bu noktada
			// transaction'ı iptal ettiği için burada yeniden sorgu yapılamaz; işlem geri alınır ve
			// kullanıcı (ilk isteğin zaten işlendiğini söyleyen) mesajı görür. Çift hareket oluşmaz.
			throw new BusinessRuleException("Bu işlem zaten kaydedildi; sayfayı yenileyin.");
		}
	}

	private Reservation lockForStaff(AppUserPrincipal staff, String code, Permission permission) {
		Reservation r = reservations.findByCodeForUpdate(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(staff, r.getBusinessId(), r.getBranchId(), permission);
		return r;
	}

	private Payment chargeOf(Reservation r, Long chargeId) {
		return payments.findById(chargeId)
			.filter(p -> p.getReservationId().equals(r.getId()) && p.getKind() == Payment.Kind.CHARGE
					&& p.isSucceeded())
			.orElseThrow(() -> new NotFoundException("Tahsilat"));
	}

	/**
	 * Açık kasayı kilitler: kasa kapanışı da aynı satırı kilitlediği için, kapanışla eşzamanlı bir nakit
	 * hareketi ya kapanıştan önce kasaya girer (beklenen tutara dahil olur) ya da "kasa kapalı" hatası alır.
	 */
	private CashSession openSession(Long branchId) {
		return cashSessions.findOpenForUpdate(branchId)
			.orElseThrow(() -> new BusinessRuleException("Nakit işlem için önce kasayı açın."));
	}

	private static void requirePositive(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new BusinessRuleException("Tutar sıfırdan büyük olmalı.");
		}
		if (amount.scale() > 2) {
			throw new BusinessRuleException("Tutar en fazla iki ondalık basamak içerebilir.");
		}
	}

	private static String clean(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

}
