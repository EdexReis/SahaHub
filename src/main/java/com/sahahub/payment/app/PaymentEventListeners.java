package com.sahahub.payment.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sahahub.booking.app.ReservationCancelled;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;

/**
 * Otomatik iadeler. AFTER_COMMIT: olayı doğuran değişiklik (iptal, ödeme kaydı) kesinleşmeden iade
 * başlatılmaz. İade kendi transaction'ında çalışır; başarısız olursa FAILED iade hareketi kalır ve
 * personel panelinde "İade başarısız" olarak görünür.
 */
@Component
class PaymentEventListeners {

	private final PaymentService paymentService;
	private final PaymentRepository payments;

	PaymentEventListeners(PaymentService paymentService, PaymentRepository payments) {
		this.paymentService = paymentService;
		this.payments = payments;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onLatePayment(LatePaymentReceived event) {
		paymentService.autoRefund(event.paymentId(), "Tutma süresi dolduktan sonra gelen ödeme");
	}

	/** Müşteri süresi içinde iptal ettiyse çevrim içi ödemeleri iade edilir. Personel iptalinde karar personelde. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCancelled(ReservationCancelled event) {
		if (!event.byCustomer()) {
			return;
		}
		for (Payment p : payments.findByReservationIdOrderByIdAsc(event.reservationId())) {
			if (p.getKind() == Payment.Kind.CHARGE && p.getMethod() == Payment.Method.ONLINE_SIM && p.isSucceeded()) {
				paymentService.autoRefund(p.getId(), "Müşteri iptali");
			}
		}
	}

}
