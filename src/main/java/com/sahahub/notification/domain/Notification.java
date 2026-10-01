package com.sahahub.notification.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Uygulama içi bildirim (bildirim merkezi). Kayıt NotificationWriter ile yazılır; burada yalnızca okunur. */
@Entity
@Table(name = "notification")
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, updatable = false)
	private String kind;

	@Column(nullable = false, updatable = false)
	private String title;

	@Column(nullable = false, updatable = false)
	private String body;

	@Column(updatable = false)
	private String link;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "read_at")
	private Instant readAt;

	protected Notification() {
	}

	public void markRead(Instant now) {
		if (readAt == null) {
			readAt = now;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getLink() {
		return link;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReadAt() {
		return readAt;
	}

}
