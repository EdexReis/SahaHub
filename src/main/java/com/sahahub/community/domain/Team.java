package com.sahahub.community.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Oyuncuların oluşturduğu takım. Kaptan, {@link TeamMember} satırındaki CAPTAIN rolüdür.
 * Davet kodu bağlantıyla paylaşılır; kaptan yenilerse eski bağlantı çalışmaz.
 */
@Entity
@Table(name = "team")
public class Team {

	public static final int MAX_MEMBERS = 25;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String city;

	@Column(name = "invite_code", nullable = false)
	private String inviteCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "disbanded_at")
	private Instant disbandedAt;

	private String description;

	@Column(name = "logo_path")
	private String logoPath;

	protected Team() {
	}

	public Team(String name, String city, String inviteCode, Instant now) {
		this.name = name.strip();
		this.city = city.strip();
		this.inviteCode = inviteCode;
		this.createdAt = now;
	}

	public void rename(String name, String city) {
		this.name = name.strip();
		this.city = city.strip();
	}

	public void describe(String description) {
		this.description = description == null || description.isBlank() ? null : description.strip();
	}

	public void changeLogo(String fileName) {
		this.logoPath = fileName;
	}

	public void changeInviteCode(String code) {
		this.inviteCode = code;
	}

	public void disband(Instant now) {
		this.disbandedAt = now;
	}

	public boolean isActive() {
		return disbandedAt == null;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

	public String getInviteCode() {
		return inviteCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDisbandedAt() {
		return disbandedAt;
	}

	public String getDescription() {
		return description;
	}

	public String getLogoPath() {
		return logoPath;
	}

}
