package com.sahahub.notification.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Gönderilecek dış mesaj (transactional outbox satırı). Yazma NotificationWriter'da (tekrar korumalı
 * INSERT); gönderim durumu OutboxDispatcher tarafından güncellenir.
 */
@Entity
@Table(name = "notification_outbox")
public class OutboxMessage {

	public enum Channel {
		EMAIL, SMS, WHATSAPP
	}

	public enum Status {
		PENDING, SENT, FAILED
	}

	/** Bu kadar başarısız denemeden sonra mesaj FAILED olur ve tekrar denenmez. */
	public static final int MAX_ATTEMPTS = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Channel channel;

	@Column(nullable = false, updatable = false)
	private String recipient;

	@Column(nullable = false, updatable = false)
	private String subject;

	@Column(nullable = false, updatable = false)
	private String body;

	@Column(name = "dedup_key", nullable = false, updatable = false)
	private String dedupKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	private String provider;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	protected OutboxMessage() {
	}

	public void markSent(String provider, Instant now) {
		this.status = Status.SENT;
		this.provider = provider;
		this.sentAt = now;
		this.attempts++;
	}

	/** Geçici hata: artan bekleme süresiyle (1, 2, 4, 8 dk) yeniden denenir. */
	public void markFailedAttempt(String error, Instant now) {
		this.attempts++;
		this.lastError = error == null ? null : error.substring(0, Math.min(300, error.length()));
		if (attempts >= MAX_ATTEMPTS) {
			this.status = Status.FAILED;
		}
		else {
			this.nextAttemptAt = now.plus(Duration.ofMinutes(1L << (attempts - 1)));
		}
	}

	public Long getId() {
		return id;
	}

	public Channel getChannel() {
		return channel;
	}

	public String getRecipient() {
		return recipient;
	}

	public String getSubject() {
		return subject;
	}

	public String getBody() {
		return body;
	}

	public String getDedupKey() {
		return dedupKey;
	}

	public Status getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getProvider() {
		return provider;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getSentAt() {
		return sentAt;
	}

}
