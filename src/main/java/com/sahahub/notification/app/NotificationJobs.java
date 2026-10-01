package com.sahahub.notification.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Zamanlanmış bildirim görevleri. Testlerde kapalıdır (sahahub.notification.jobs-enabled=false);
 * testler servisleri doğrudan çağırır.
 */
@Component
@ConditionalOnProperty(name = "sahahub.notification.jobs-enabled", havingValue = "true", matchIfMissing = true)
class NotificationJobs {

	private final OutboxDispatcher dispatcher;
	private final ReservationNotifications notifications;

	NotificationJobs(OutboxDispatcher dispatcher, ReservationNotifications notifications) {
		this.dispatcher = dispatcher;
		this.notifications = notifications;
	}

	@Scheduled(fixedDelayString = "${sahahub.notification.dispatch-interval:PT10S}")
	void dispatch() {
		while (dispatcher.dispatchDue() == OutboxDispatcher.BATCH_SIZE) {
			// Grup doluysa sırada başka mesaj olabilir
		}
	}

	@Scheduled(fixedDelayString = "${sahahub.notification.reminder-interval:PT10M}", initialDelayString = "PT20S")
	void reminders() {
		notifications.enqueueReminders();
	}

}
