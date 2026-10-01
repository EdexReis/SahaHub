package com.sahahub.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Parola sıfırlama bağlantısı. Bağlantıdaki rastgele değerin kendisi saklanmaz, yalnızca SHA-256 özeti:
 * veritabanını okuyabilen biri de geçerli bir bağlantı üretemez. Süreli ve tek kullanımlıktır.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected PasswordResetToken() {
	}

	public PasswordResetToken(Long userId, String tokenHash, Instant now, Instant expiresAt) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	public boolean isUsableAt(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	/** Kullanıldı veya geçersiz kılındı (yeni bağlantı istendi / parola değişti). */
	public void consume(Instant now) {
		if (usedAt == null) {
			this.usedAt = now;
		}
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

}
