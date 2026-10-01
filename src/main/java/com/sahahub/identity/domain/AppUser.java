package com.sahahub.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Sisteme giriş yapabilen kişi. Her kullanıcı müşteri olarak rezervasyon yapabilir;
 * personel yetkileri {@link StaffMembership} ile, platform yöneticiliği bu sınıftaki
 * bayrakla verilir. Takım kaptanlığı bir rol değildir, takım-üye ilişkisidir.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	private String phone;

	@Column(name = "platform_admin", nullable = false)
	private boolean platformAdmin;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AppUser() {
	}

	public AppUser(String email, String passwordHash, String fullName, String phone, Instant createdAt) {
		this.email = normalizeEmail(email);
		this.passwordHash = passwordHash;
		this.fullName = fullName.strip();
		this.phone = phone == null || phone.isBlank() ? null : phone.strip();
		this.createdAt = createdAt;
	}

	public static String normalizeEmail(String email) {
		return email.strip().toLowerCase(java.util.Locale.ROOT);
	}

	public void grantPlatformAdmin() {
		this.platformAdmin = true;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public String getPhone() {
		return phone;
	}

	public boolean isPlatformAdmin() {
		return platformAdmin;
	}

	public boolean isEnabled() {
		return enabled;
	}

}
