package com.sahahub.community.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Takım maçı hatırlatmalarını üretir. Rezervasyon hatırlatmasıyla aynı aralıkta çalışır ve aynı ayarla kapatılır
 * (testlerde kapalı; testler servisi doğrudan çağırır). Gönderimi bildirim modülünün outbox görevi yapar.
 */
@Component
@ConditionalOnProperty(name = "sahahub.notification.jobs-enabled", havingValue = "true", matchIfMissing = true)
class TeamMatchReminderJob {

	private final TeamMatchService service;

	TeamMatchReminderJob(TeamMatchService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${sahahub.notification.reminder-interval:PT10M}", initialDelayString = "PT25S")
	void reminders() {
		service.enqueueReminders();
	}

}
