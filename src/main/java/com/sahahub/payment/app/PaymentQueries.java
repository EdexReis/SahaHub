package com.sahahub.payment.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.PaymentStatusPort;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.domain.PaymentSummary;

/** Ödeme durumu hesapları (yalnızca okuma). Rezervasyon modülüne etiket sağlar. */
@Service
@Transactional(readOnly = true)
public class PaymentQueries implements PaymentStatusPort {

	/** Ekranda gösterilen bir ödeme hareketi. */
	public record PaymentRow(Long id, Payment.Kind kind, Payment.Method method, Payment.Status status,
			BigDecimal amount, String currency, ZonedDateTime createdAt, String payerName, String note,
			String failureReason, Long relatedPaymentId, BigDecimal refundable, boolean reversible) {
	}

	/** Rezervasyonun ödeme bölümü. */
	public record ReservationPayments(PaymentSummary summary, List<PaymentRow> rows) {
	}

	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final Clock clock;

	public PaymentQueries(PaymentRepository payments, ReservationRepository reservations, Clock clock) {
		this.payments = payments;
		this.reservations = reservations;
		this.clock = clock;
	}

	/**
	 * Ödenmesi gereken: iptal edilen veya süresi dolan rezervasyonda 0 (ödenen her şey iade edilebilir),
	 * diğer durumlarda rezervasyon toplamı. Kapora, toplam üzerinden rezervasyonun kopyalanmış kuralıyla.
	 */
	public static PaymentSummary summarize(Reservation r, List<Payment> list) {
		boolean nothingDue = r.getStatus() == ReservationStatus.CANCELLED || r.getStatus() == ReservationStatus.EXPIRED;
		BigDecimal due = nothingDue ? BigDecimal.ZERO : r.getTotalAmount();
		BigDecimal deposit = nothingDue ? BigDecimal.ZERO : r.depositPolicy().depositFor(r.getTotalAmount());
		return PaymentSummary.of(due, deposit, list, r.getCurrency());
	}

	public PaymentSummary summary(Reservation r) {
		return summarize(r, payments.findByReservationIdOrderByIdAsc(r.getId()));
	}

	public ReservationPayments forReservation(Reservation r, ZoneId zone) {
		List<Payment> list = payments.findByReservationIdOrderByIdAsc(r.getId());
		List<PaymentRow> rows = new ArrayList<>();
		for (Payment p : list) {
			BigDecimal refundable = p.getKind() == Payment.Kind.CHARGE && p.isSucceeded()
					? refundableFor(p, list)
					: BigDecimal.ZERO;
			boolean reversible = p.getKind() == Payment.Kind.CHARGE && p.isSucceeded()
					&& (p.getMethod() == Payment.Method.CASH || p.getMethod() == Payment.Method.MANUAL_POS)
					&& list.stream().noneMatch(q -> p.getId().equals(q.getRelatedPaymentId()));
			rows.add(new PaymentRow(p.getId(), p.getKind(), p.getMethod(), p.getStatus(), p.getAmount(),
					p.getCurrency(), p.getCreatedAt().atZone(zone), p.getPayerName(), p.getNote(),
					p.getFailureReason(), p.getRelatedPaymentId(), refundable, reversible));
		}
		return new ReservationPayments(summarize(r, list), rows);
	}

	/**
	 * Bir tahsilattan daha iade edilebilecek tutar: tahsilat − (başarılı ya da bekleyen) iadeler − ters kayıt.
	 * Bekleyen iadeler de düşülür; böylece sonuç beklenirken ikinci bir iade aynı parayı tekrar iade edemez.
	 */
	static BigDecimal refundableFor(Payment charge, List<Payment> all) {
		BigDecimal used = BigDecimal.ZERO;
		for (Payment q : all) {
			if (charge.getId().equals(q.getRelatedPaymentId()) && q.getStatus() != Payment.Status.FAILED) {
				used = used.add(q.getAmount());
			}
		}
		return charge.getAmount().subtract(used).max(BigDecimal.ZERO);
	}

	@Override
	public Map<Long, Badge> badges(Collection<Reservation> list) {
		Map<Long, Badge> result = new HashMap<>();
		if (list.isEmpty()) {
			return result;
		}
		Map<Long, List<Payment>> byReservation = new HashMap<>();
		for (Payment p : payments.findByReservationIdInOrderByIdAsc(list.stream().map(Reservation::getId).toList())) {
			byReservation.computeIfAbsent(p.getReservationId(), k -> new ArrayList<>()).add(p);
		}
		for (Reservation r : list) {
			PaymentSummary s = summarize(r, byReservation.getOrDefault(r.getId(), List.of()));
			PaymentSummary.State state = s.state();
			if (state == PaymentSummary.State.DEPOSIT_DUE || state == PaymentSummary.State.PAID
					|| state == PaymentSummary.State.PARTIAL || state == PaymentSummary.State.REFUND_DUE) {
				result.put(r.getId(), new Badge(state.name(), state.label()));
			}
		}
		return result;
	}

	@Override
	public boolean depositCovered(Reservation r) {
		PaymentSummary s = summary(r);
		return s.paid().compareTo(s.deposit()) >= 0;
	}

	/** Şubede önümüzdeki günlerde kaporası ödenmemiş onaylı rezervasyonlar. */
	public List<Reservation> pendingDeposits(Long branchId, int days) {
		Instant now = Instant.now(clock);
		List<Reservation> upcoming = reservations.findForCalendar(branchId, now, now.plus(Duration.ofDays(days)),
				List.of(ReservationStatus.CONFIRMED));
		Map<Long, Badge> b = badges(upcoming);
		return upcoming.stream()
			.filter(r -> b.containsKey(r.getId()) && PaymentSummary.State.DEPOSIT_DUE.name().equals(b.get(r.getId()).state()))
			.toList();
	}

	public List<Payment> pendingTransfers(Long branchId) {
		return payments.findPendingTransfers(branchId);
	}

	public List<Payment> failedRefunds(Long branchId) {
		return payments.findUnresolvedFailedRefunds(branchId);
	}

}
