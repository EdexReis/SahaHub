package com.sahahub.payment.provider;

import java.math.BigDecimal;

/**
 * Çevrim içi ödeme sağlayıcısı adaptörü. Uygulama yalnızca bu arayüzü bilir; gerçek bir sağlayıcıya
 * (iyzico, PayTR, Stripe vb.) geçmek için bu arayüzün yeni bir uygulaması yazılır ve sağlayıcının
 * webhook'u PaymentWebhookController'a benzer bir uca bağlanır.
 * <p>
 * Kart bilgisi uygulamaya HİÇ gelmez: müşteri sağlayıcının sayfasına yönlendirilir, sonuç imzalı
 * bildirimle (webhook) gelir.
 */
public interface PaymentProvider {

	/** Sağlayıcının kısa adı; webhook olaylarının tekilliği (provider, event_id) için kullanılır. */
	String name();

	/**
	 * Ödeme oturumu açar. Aynı idempotencyKey ile tekrar çağrılırsa aynı oturumu döndürmelidir.
	 *
	 * @return sağlayıcı referansı ve müşterinin yönlendirileceği adres
	 */
	ChargeSession createCharge(String idempotencyKey, BigDecimal amount, String currency, String description);

	/** Başarılı bir ödemenin tamamını veya bir kısmını iade eder. Sonuç senkron döner. */
	RefundOutcome refund(String providerRef, BigDecimal amount, String idempotencyKey);

	record ChargeSession(String providerRef, String redirectPath) {
	}

	record RefundOutcome(boolean succeeded, String failureReason) {

		public static RefundOutcome ok() {
			return new RefundOutcome(true, null);
		}

		public static RefundOutcome failed(String reason) {
			return new RefundOutcome(false, reason);
		}

	}

}
