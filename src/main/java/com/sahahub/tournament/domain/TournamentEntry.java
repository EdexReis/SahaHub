package com.sahahub.tournament.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ligdeki takım. Şube ligine platformda hesabı olmayan takımlar da katılır; bu yüzden yalnızca ad tutulur.
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

	protected TournamentEntry() {
	}

	public TournamentEntry(Long tournamentId, String name, Instant now) {
		this.tournamentId = tournamentId;
		this.name = name.strip();
		this.createdAt = now;
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
