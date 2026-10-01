package com.sahahub.tournament.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ligdeki takım. Şube ligine platformda hesabı olmayan takımlar da katılır; bu yüzden ad her zaman tutulur.
 * İsteğe bağlı olarak platformdaki bir takıma bağlanır: personel {@code linkCode} bağlantısını paylaşır, takımın
 * kaptanı açıp kendi takımını seçer. Bağlantı kaldırılınca kod yenilenir (eski bağlantı çalışmaz).
 */
@Entity
@Table(name = "tournament_entry")
public class TournamentEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "tournament_id", nullable = false, updatable = false)
	private Long tournamentId;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "link_code", nullable = false)
	private String linkCode;

	@Column(name = "team_id")
	private Long teamId;

	@Column(name = "linked_at")
	private Instant linkedAt;

	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	protected TournamentEntry() {
	}

	/** Takım bağlantısı için tahmin edilemez kod (12 karakter, karışabilecek harfler yok). */
	public static String newLinkCode() {
		StringBuilder sb = new StringBuilder(12);
		for (int i = 0; i < 12; i++) {
			sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
		}
		return sb.toString();
	}

	public TournamentEntry(Long tournamentId, String name, String linkCode, Instant now) {
		this.tournamentId = tournamentId;
		this.name = name.strip();
		this.linkCode = linkCode;
		this.createdAt = now;
	}

	public void link(Long teamId, Instant now) {
		if (this.teamId != null) {
			throw new IllegalStateException("Kayıt zaten bir takıma bağlı: " + id);
		}
		this.teamId = teamId;
		this.linkedAt = now;
	}

	/** Bağlantıyı kaldırır ve yeni kod verir. */
	public void unlink(String newCode) {
		this.teamId = null;
		this.linkedAt = null;
		this.linkCode = newCode;
	}

	public boolean isLinked() {
		return teamId != null;
	}

	public String getLinkCode() {
		return linkCode;
	}

	public Long getTeamId() {
		return teamId;
	}

	public Long getId() {
		return id;
	}

	public Long getTournamentId() {
		return tournamentId;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
