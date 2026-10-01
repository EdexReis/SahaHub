package com.sahahub.payment.provider;

/**
 * Sağlayıcı bildirimlerini alan uç. HTTP denetleyicisi ve simülasyon dağıtıcısı aynı yolu kullanır;
 * imza doğrulaması ve tekrar koruması her iki yolda da aynıdır.
 */
public interface WebhookEndpoint {

	enum Result {
		PROCESSED, DUPLICATE, IGNORED
	}

	/** @throws SecurityException imza geçersizse */
	Result receive(String rawBody, String signature);

}
