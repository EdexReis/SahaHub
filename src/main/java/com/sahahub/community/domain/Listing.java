package com.sahahub.community.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Takımın "oyuncu arıyoruz" veya "rakip arıyoruz" ilanı.
 *
 * <pre>
 * OPEN ──kontenjan doldu──► FILLED
 *   └──ilan sahibi kapattı / bağlı rezervasyon iptal edildi──► CLOSED
 * </pre>
 *
 * Süre dolumu ayrı durum değildir: {@link #isAcceptingAt(Instant)} expires_at'e bakar.
 */
@Entity
@Table(name = "listing")
public class Listing {

	public enum Kind {

		PLAYERS_WANTED("Oyuncu arıyoruz"), OPPONENT_WANTED("Rakip arıyoruz");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public enum Level {

		CASUAL("Keyif maçı"), INTERMEDIATE("Orta seviye"), COMPETITIVE("İddialı");

		private final String label;

		Level(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public enum Status {

		OPEN("Açık"), FILLED("Doldu"), CLOSED("Kapatıldı");

		private final String label;

		Status(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Kind kind;

	@Column(name = "team_id", nullable = false, updatable = false)
	private Long teamId;

	@Column(name = "author_id", nullable = false, updatable = false)
	private Long authorId;

	@Column(name = "reservation_id", updatable = false)
	private Long reservationId;

	@Column(nullable = false, updatable = false)
	private String city;

	@Column(updatable = false)
	private String district;

	@Column(name = "play_at", updatable = false)
	private Instant playAt;

	@Column(name = "players_needed", updatable = false)
	private Integer playersNeeded;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Level level;

	@Column(updatable = false)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Version
	private long version;

	protected Listing() {
	}

	public Listing(Kind kind, Long teamId, Long authorId, Long reservationId, String city, String district,
			Instant playAt, Integer playersNeeded, Level level, String note, Instant expiresAt, Instant now) {
		if ((kind == Kind.PLAYERS_WANTED) != (playersNeeded != null)) {
			throw new IllegalArgumentException("Oyuncu sayısı yalnızca 'oyuncu arıyoruz' ilanında olur");
		}
		if (!expiresAt.isAfter(now)) {
			throw new IllegalArgumentException("İlanın bitişi gelecekte olmalı");
		}
		this.kind = kind;
		this.teamId = teamId;
		this.authorId = authorId;
		this.reservationId = reservationId;
		this.city = city;
		this.district = district;
		this.playAt = playAt;
		this.playersNeeded = playersNeeded;
		this.level = level;
		this.note = note;
		this.status = Status.OPEN;
		this.expiresAt = expiresAt;
		this.createdAt = now;
	}

	/** Başvuru alınabilir mi: açık ve süresi dolmamış. */
	public boolean isAcceptingAt(Instant now) {
		return status == Status.OPEN && now.isBefore(expiresAt);
	}

	/** Kabul sayısı kontenjana ulaştıysa ilan dolar. Rakip ilanında kontenjan 1'dir. */
	public void onAccepted(long acceptedCount, Instant now) {
		if (acceptedCount >= capacity()) {
			this.status = Status.FILLED;
			this.closedAt = now;
		}
	}

	public int capacity() {
		return kind == Kind.OPPONENT_WANTED ? 1 : playersNeeded;
	}

	public void close(Instant now) {
		if (status != Status.OPEN) {
			throw new IllegalStateException("İlan zaten kapalı: " + id);
		}
		this.status = Status.CLOSED;
		this.closedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Kind getKind() {
		return kind;
	}

	public Long getTeamId() {
		return teamId;
	}

	public Long getAuthorId() {
		return authorId;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public String getCity() {
		return city;
	}

	public String getDistrict() {
		return district;
	}

	public Instant getPlayAt() {
		return playAt;
	}

	public Integer getPlayersNeeded() {
		return playersNeeded;
	}

	public Level getLevel() {
		return level;
	}

	public String getNote() {
		return note;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

}
