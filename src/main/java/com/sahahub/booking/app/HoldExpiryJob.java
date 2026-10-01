package com.sahahub.booking.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Zamanlanmış görev: tüm süresi dolmuş tutmalar bitene kadar gruplar hâlinde işler.
 * Testlerde kapatılır (sahahub.booking.expiry-job-enabled=false); testler servisi doğrudan çağırır.
 */
@Component
@ConditionalOnProperty(name = "sahahub.booking.expiry-job-enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryJob {

	private final HoldExpiryService service;

	HoldExpiryJob(HoldExpiryService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${sahahub.booking.expiry-sweep-interval:PT30S}")
	void run() {
		while (service.expireDueHolds() == HoldExpiryService.BATCH_SIZE) {
			// Grup doluysa sırada başka kayıt olabilir; devam et
		}
	}

}
