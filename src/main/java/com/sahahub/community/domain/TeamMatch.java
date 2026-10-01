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
 * Takımın maçı. Kaptanın onaylı rezervasyonuna bağlıysa yer ve saat oradan gelir; değilse serbest (başka tesis).
 * Takım bir şube ligine bağlıysa lig maçları da buraya yansır ({@code tournamentMatchId}): yer, saat, rakip ve skor
 * ligden gelir; kaptan değiştiremez.
 *
 * <pre>
 * SCHEDULED ──skor (maç başladıktan sonra)──► PLAYED
 *     └──kaptan iptal etti / bağlı rezervasyon iptal edildi──► CANCELLED
 * </pre>
 */
@Entity
@Table(name = "team_match")
public class TeamMatch {

	public enum Status {

		SCHEDULED("Planlandı"), PLAYED("Oynandı"), CANCELLED("İptal edildi");

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

	@Column(name = "team_id", nullable = false, updatable = false)
	private Long teamId;

	@Column(name = "reservation_id", updatable = false)
	private Long reservationId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(nullable = false)
	private String place;

	private String opponent;

	private String note;

	@Column(name = "tournament_id", updatable = false)
	private Long tournamentId;

	@Column(name = "tournament_match_id", updatable = false)
	private Long tournamentMatchId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "our_score")
	private Integer ourScore;

	@Column(name = "their_score")
	private Integer theirScore;

	@Column(name = "created_by", nullable = false, updatable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Version
	private long version;

	protected TeamMatch() {
	}

	public TeamMatch(Long teamId, Long reservationId, Instant startsAt, String place, String opponent, String note,
			Long createdBy, Instant now) {
		this.teamId = teamId;
		this.reservationId = reservationId;
		this.startsAt = startsAt;
		this.place = place;
		this.opponent = opponent;
		this.note = note;
		this.status = Status.SCHEDULED;
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	/** Lig maçının takım tarafındaki kopyası. */
	public static TeamMatch fromLeague(Long teamId, Long tournamentId, Long tournamentMatchId, Instant startsAt,
			String place, String opponent, String note, Long createdBy, Instant now) {
		TeamMatch m = new TeamMatch(teamId, null, startsAt, place, opponent, note, createdBy, now);
		m.tournamentId = tournamentId;
		m.tournamentMatchId = tournamentMatchId;
		return m;
	}

	/** Lig maçı yeniden planlandı ya da rakibi değişti (eleme düzeltmesi). Yalnızca planlı lig maçında. */
	public boolean syncFromLeague(Instant startsAt, String place, String opponent, String note) {
		if (!isLeague() || status != Status.SCHEDULED) {
			throw new IllegalStateException("Yalnızca planlı lig maçı güncellenir: " + id);
		}
		boolean moved = !this.startsAt.equals(startsAt) || !this.place.equals(place);
		this.startsAt = startsAt;
		this.place = place;
		this.opponent = opponent;
		this.note = note;
		return moved;
	}

	public boolean isLeague() {
		return tournamentMatchId != null;
	}

	public boolean isOpenForAnswers(Instant now) {
		return status == Status.SCHEDULED && now.isBefore(startsAt);
	}

	public void cancel() {
		if (status != Status.SCHEDULED) {
			throw new IllegalStateException("Yalnızca planlanmış takım maçı iptal edilir: " + id);
		}
		this.status = Status.CANCELLED;
	}

	/** Skor maç başladıktan sonra girilir; oynanmış maçta düzeltilebilir. */
	public void recordScore(int ours, int theirs, Instant now) {
		if (status == Status.CANCELLED) {
			throw new IllegalStateException("İptal edilmiş maçın skoru girilemez: " + id);
		}
		if (now.isBefore(startsAt)) {
			throw new IllegalStateException("Maç başlamadan skor girilemez: " + id);
		}
		if (ours < 0 || theirs < 0 || ours > 99 || theirs > 99) {
			throw new IllegalArgumentException("Skor 0-99 arasında olmalı");
		}
		this.ourScore = ours;
		this.theirScore = theirs;
		this.status = Status.PLAYED;
	}

	public Long getId() {
		return id;
	}

	public Long getTeamId() {
		return teamId;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public String getPlace() {
		return place;
	}

	public String getOpponent() {
		return opponent;
	}

	public String getNote() {
		return note;
	}

	public Status getStatus() {
		return status;
	}

	public Integer getOurScore() {
		return ourScore;
	}

	public Integer getTheirScore() {
		return theirScore;
	}

	public Long getTournamentId() {
		return tournamentId;
	}

	public Long getTournamentMatchId() {
		return tournamentMatchId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
