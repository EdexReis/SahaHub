package com.sahahub.payment.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * sahahub.payment.simulation.* ayarları.
 *
 * @param webhookSecret imza anahtarı; canlı ortamda ortam değişkeninden gelmeli
 * @param delay         "gecikmeli bildirim" senaryosunda bildirimin kaç saniye sonra geleceği
 */
@ConfigurationProperties("sahahub.payment.simulation")
public record SimulationProperties(String webhookSecret, Duration delay) {

	public SimulationProperties {
		if (webhookSecret == null || webhookSecret.isBlank()) {
			throw new IllegalStateException(
					"sahahub.payment.simulation.webhook-secret tanımlı değil (PAYMENT_SIM_WEBHOOK_SECRET ortam değişkeni).");
		}
		delay = delay == null ? Duration.ofSeconds(60) : delay;
	}

}
