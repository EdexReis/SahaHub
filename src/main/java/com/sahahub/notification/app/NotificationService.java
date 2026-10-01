package com.sahahub.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.notification.domain.Notification;
import com.sahahub.notification.domain.NotificationRepository;
import com.sahahub.notification.domain.OutboxMessage;
import com.sahahub.notification.domain.OutboxRepository;
import com.sahahub.shared.domain.BusinessRuleException;

/** Kullanıcının bildirim kutusu, bildirim tercihleri ve yöneticinin demo mesaj kutusu. */
@Service
public class NotificationService {

	public record Preferences(boolean email, boolean sms, String phone, String emailAddress) {
	}

	private final NotificationRepository notifications;
	private final OutboxRepository outbox;
	private final AppUserRepository users;
	private final Clock clock;

	public NotificationService(NotificationRepository notifications, OutboxRepository outbox, AppUserRepository users,
			Clock clock) {
		this.notifications = notifications;
		this.outbox = outbox;
		this.users = users;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Page<Notification> inbox(Long userId, int page) {
		return notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(0, page), 30));
	}

	@Transactional(readOnly = true)
	public long unreadCount(Long userId) {
		return notifications.countByUserIdAndReadAtIsNull(userId);
	}

	@Transactional
	public int markAllRead(Long userId) {
		return notifications.markAllRead(userId, Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public Preferences preferences(Long userId) {
		AppUser u = users.findById(userId).orElseThrow();
		return new Preferences(u.isNotifyEmail(), u.isNotifySms(), u.getPhone(), u.getEmail());
	}

	@Transactional
	public void updatePreferences(Long userId, boolean email, boolean sms, String phone) {
		String p = phone == null ? "" : phone.strip();
		if (!p.isEmpty() && !p.matches("[+]?[0-9 ()-]{7,20}")) {
			throw new BusinessRuleException("Telefon numarası geçersiz. Örnek: 0532 000 00 00");
		}
		if (sms && p.isEmpty()) {
			throw new BusinessRuleException("SMS bildirimi için telefon numarası gerekli.");
		}
		users.findById(userId).orElseThrow().changeNotificationPreferences(email, sms, p);
	}

	/** Demo SMS/WhatsApp kutusu: gönderilmeyen kısa mesajlar (yalnızca platform yöneticisi). */
	@Transactional(readOnly = true)
	public List<OutboxMessage> demoMessages() {
		return outbox.findByChannelInOrderByIdDesc(EnumSet.of(OutboxMessage.Channel.SMS, OutboxMessage.Channel.WHATSAPP),
				PageRequest.of(0, 100));
	}

}
