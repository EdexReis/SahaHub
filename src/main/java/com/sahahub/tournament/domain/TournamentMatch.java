package com.sahahub.tournament.domain;

import java.time.Instant;

import com.sahahub.shared.domain.TimeRange;

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
 * Lig maçı.
 *
 * <pre>
 * UNSCHEDULED ──planla──► SCHEDULED ──skor (maç başladıktan sonra)──► PLAYED
 *       ▲                    │  ▲                                       │
 *       └──planı kaldır──────┘  └──yeniden planla (yeni saat)            └──skor düzeltme (PLAYED kalır)
 * </pre>
 *
 * Saha doluluğu bu sınıfta değil, pitch_occupancy'de tutulur (kaynak TOURNAMENT_MATCH); planlama ve
 * kaldırma servis katmanında aynı transaction'da doluluğu da günceller.
 */
@Entity
@Table(name = "tournament_match")
public class TournamentMatch {

	public enum Status {

		UNSCHEDULED("Planlanmadı"), SCHEDULED("Planlandı"), PLAYED("Oynandı");

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

	@Column(name = "tournament_id", nullable = false, updatable = false)
	private Long tournamentId;

	@Column(nullable = false, updatable = false)
	private int round;

	@Column(name = "home_entry_id", nullable = false, updatable = false)
	private Long homeEntryId;

	@Column(name = "away_entry_id", nullable = false, updatable = false)
	private Long awayEntryId;

	@Column(name = "pitch_id")
	private Long pitchId;

	@Column(name = "starts_at")
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "home_score")
	private Integer homeScore;

	@Column(name = "away_score")
	private Integer awayScore;

	@Version
	private long version;

	protected TournamentMatch() {
	}

	public TournamentMatch(Long tournamentId, int round, Long homeEntryId, Long awayEntryId) {
		if (homeEntryId.equals(awayEntryId)) {
			throw new IllegalArgumentException("Bir takım kendisiyle eşleşemez");
		}
		this.tournamentId = tournamentId;
		this.round = round;
		this.homeEntryId = homeEntryId;
		this.awayEntryId = awayEntryId;
		this.status = Status.UNSCHEDULED;
	}

	/** Planlar veya yeniden planlar. Oynanmış maçın saati değişmez. */
	public void schedule(Long pitchId, TimeRange play) {
		if (status == Status.PLAYED) {
			throw new IllegalStateException("Oynanmış maç yeniden planlanamaz: " + id);
		}
		this.pitchId = pitchId;
		this.startsAt = play.start();
		this.endsAt = play.end();
		this.status = Status.SCHEDULED;
	}

	public void unschedule() {
		if (status != Status.SCHEDULED) {
			throw new IllegalStateException("Yalnızca planlanmış maçın planı kaldırılır: " + id);
		}
		this.pitchId = null;
		this.startsAt = null;
		this.endsAt = null;
		this.status = Status.UNSCHEDULED;
	}

	/** Skor maç başladıktan sonra girilir; oynanmış maçta düzeltme yapılabilir. */
	public void recordResult(int home, int away, Instant now) {
		if (status == Status.UNSCHEDULED) {
			throw new IllegalStateException("Planlanmamış maçın skoru girilemez: " + id);
		}
		if (now.isBefore(startsAt)) {
			throw new IllegalStateException("Maç başlamadan skor girilemez: " + id);
		}
		if (home < 0 || away < 0 || home > 99 || away > 99) {
			throw new IllegalArgumentException("Skor 0-99 arasında olmalı");
		}
		this.homeScore = home;
		this.awayScore = away;
		this.status = Status.PLAYED;
	}

	public boolean canRecordResultAt(Instant now) {
		return status != Status.UNSCHEDULED && !now.isBefore(startsAt);
	}

	public boolean isPlayed() {
		return status == Status.PLAYED;
	}

	public TimeRange play() {
		return startsAt == null ? null : new TimeRange(startsAt, endsAt);
	}

	public Long getId() {
		return id;
	}

	public Long getTournamentId() {
		return tournamentId;
	}

	public int getRound() {
		return round;
	}

	public Long getHomeEntryId() {
		return homeEntryId;
	}

	public Long getAwayEntryId() {
		return awayEntryId;
	}

	public Long getPitchId() {
		return pitchId;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public Status getStatus() {
		return status;
	}

	public Integer getHomeScore() {
		return homeScore;
	}

	public Integer getAwayScore() {
		return awayScore;
	}

}
