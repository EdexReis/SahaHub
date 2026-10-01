package com.sahahub.payment.app;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.PaymentQueries.ReservationPayments;
import com.sahahub.payment.domain.CashSessionRepository;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.pricing.app.PricingAdminService.ExtraRow;
import com.sahahub.pricing.domain.ExtraServiceRepository;
import com.sahahub.shared.domain.Money;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Ekranlar için ödeme bilgisini toplar ve kimin neyi görebileceğini kontrol eder.
 * Müşteri yalnızca kendi rezervasyonunu, personel şubesindekileri görür.
 */
@Service
@Transactional(readOnly = true)
public class PaymentPanelService {

	private static final DateTimeFormatter ALERT_TIME = DateTimeFormatter.ofPattern("d MMM EEE HH:mm",
			Locale.forLanguageTag("tr"));

	/** Rezervasyon ekranındaki ödeme bölümü ve kullanıcının yapabilecekleri. */
	public record Panel(ReservationPayments payments, List<ExtraRow> extras, String bankIban, boolean canCollect,
			boolean canRefund, boolean canDiscount, boolean cashOpen) {
	}

	/** Uyarı satırı: tıklanınca ilgili rezervasyon paneli açılır. */
	public record AlertItem(String href, String title, String detail) {
	}

	/** Takvim yan paneli uyarıları. */
	public record BranchAlerts(List<AlertItem> pendingDeposits, List<AlertItem> pendingTransfers,
			List<AlertItem> failedRefunds, boolean cashOpen) {

		public boolean any() {
			return !pendingDeposits.isEmpty() || !pendingTransfers.isEmpty() || !failedRefunds.isEmpty();
		}

	}

	private final ReservationRepository reservations;
	private final PaymentQueries queries;
	private final ExtraServiceRepository extras;
	private final CashSessionRepository sessions;
	private final PaymentRepository payments;
	private final CatalogService catalog;
	private final AccessGuard guard;

	public PaymentPanelService(ReservationRepository reservations, PaymentQueries queries,
			ExtraServiceRepository extras, CashSessionRepository sessions, PaymentRepository payments,
			CatalogService catalog, AccessGuard guard) {
		this.reservations = reservations;
		this.payments = payments;
		this.queries = queries;
		this.extras = extras;
		this.sessions = sessions;
		this.catalog = catalog;
		this.guard = guard;
	}

	public Panel forCustomer(AppUserPrincipal user, String code) {
		Reservation r = reservations.findByCode(code)
			.filter(x -> user.id().equals(x.getCustomerId()))
			.orElseThrow(() -> new NotFoundException("Rezervasyon"));
		BranchContext bc = catalog.branchContext(r.getBranchId());
		return new Panel(queries.forReservation(r, bc.branch().zone()),
				extrasOf(r.getBranchId()), bc.branch().getBankIban(), false, false,
				false, false);
	}

	public Panel forStaff(AppUserPrincipal staff, String code) {
		Reservation r = reservations.findByCode(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(staff, r.getBusinessId(), r.getBranchId(), Permission.CALENDAR_VIEW);
		BranchContext bc = catalog.branchContext(r.getBranchId());
		return new Panel(queries.forReservation(r, bc.branch().zone()),
				extrasOf(r.getBranchId()), bc.branch().getBankIban(),
				guard.can(staff, r.getBusinessId(), r.getBranchId(), Permission.PAYMENT_COLLECT),
				guard.can(staff, r.getBusinessId(), r.getBranchId(), Permission.PAYMENT_REFUND),
				guard.can(staff, r.getBusinessId(), r.getBranchId(), Permission.DISCOUNT_APPLY),
				sessions.findOpen(r.getBranchId()).isPresent());
	}

	private List<ExtraRow> extrasOf(Long branchId) {
		return extras.findByBranchIdAndActiveTrueOrderByName(branchId).stream().map(ExtraRow::of).toList();
	}

	public BranchAlerts alerts(AppUserPrincipal staff, Long branchId) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(staff, bc.business().getId(), branchId, Permission.CALENDAR_VIEW);
		ZoneId zone = bc.branch().zone();
		List<AlertItem> deposits = queries.pendingDeposits(branchId, 7).stream()
			.map(r -> new AlertItem("/isletme/rezervasyonlar/" + r.getCode(),
					r.getStartsAt().atZone(zone).format(ALERT_TIME),
					"Kapora: " + Money.format(queries.summary(r).depositRemaining(), r.getCurrency())))
			.toList();
		List<AlertItem> transfers = queries.pendingTransfers(branchId).stream()
			.map(p -> new AlertItem("/isletme/odemeler/" + p.getId(), p.getPayerName(),
					Money.format(p.getAmount(), p.getCurrency())))
			.toList();
		List<AlertItem> refunds = queries.failedRefunds(branchId).stream()
			.map(p -> new AlertItem("/isletme/odemeler/" + p.getId(), "İade başarısız: " + Money.format(p.getAmount(), p.getCurrency()),
					p.getFailureReason()))
			.toList();
		return new BranchAlerts(deposits, transfers, refunds, sessions.findOpen(branchId).isPresent());
	}

	/** Bir ödeme hareketinin rezervasyon kodu (işlemden sonra panele dönmek için; yetki kontrolüyle). */
	public String reservationCodeOfPayment(AppUserPrincipal staff, Long paymentId) {
		Payment p = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Ödeme"));
		guard.requireBranch(staff, p.getBusinessId(), p.getBranchId(), Permission.CALENDAR_VIEW);
		return reservations.findById(p.getReservationId()).orElseThrow().getCode();
	}

}
