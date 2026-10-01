package com.sahahub.tournament.domain;

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
 * Şubenin düzenlediği lig.
 *
 * <pre>
 * DRAFT (takımlar eklenir) ──fikstür oluşturuldu──► ACTIVE (maçlar planlanır, skorlar girilir)
 *                                                    └──tüm maçlar oynandı, lig bitirildi──► FINISHED
 * </pre>
 */
@Entity
@Table(name = "tournament")
public class Tournament {

	public static final int MIN_ENTRIES = 3;
	public static final int MAX_ENTRIES = 20;

	public enum Format {
		LEAGUE
	}

	public enum Status {

		DRAFT("Hazırlanıyor"), ACTIVE("Sürüyor"), FINISHED("Tamamlandı");

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

	@Column(name = "business_id", nullable = false, updatable = false)
	private Long businessId;

	@Column(name = "branch_id", nullable = false, updatable = false)
	private Long branchId;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Format format;

	@Column(name = "double_round", nullable = false)
	private boolean doubleRound;

	@Column(name = "points_win", nullable = false)
	private int pointsWin;

	@Column(name = "points_draw", nullable = false)
	private int pointsDraw;

	@Column(name = "points_loss", nullable = false)
	private int pointsLoss;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "created_by", nullable = false, updatable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Version
	private long version;

	protected Tournament() {
	}

	public Tournament(Long businessId, Long branchId, String name, boolean doubleRound, int pointsWin, int pointsDraw,
			int pointsLoss, Long createdBy, Instant now) {
		if (pointsWin < pointsDraw || pointsDraw < pointsLoss) {
			throw new IllegalArgumentException("Puanlar galibiyet ≥ beraberlik ≥ mağlubiyet olmalı");
		}
		this.businessId = businessId;
		this.branchId = branchId;
		this.name = name.strip();
		this.format = Format.LEAGUE;
		this.doubleRound = doubleRound;
		this.pointsWin = pointsWin;
		this.pointsDraw = pointsDraw;
		this.pointsLoss = pointsLoss;
		this.status = Status.DRAFT;
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	public void start(Instant now) {
		require(Status.DRAFT);
		this.status = Status.ACTIVE;
		this.startedAt = now;
	}

	public void finish(Instant now) {
		require(Status.ACTIVE);
		this.status = Status.FINISHED;
		this.finishedAt = now;
	}

	public boolean isDraft() {
		return status == Status.DRAFT;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	/** Herkese açık sayfada görünür mü (taslak lig görünmez). */
	public boolean isPublic() {
		return status != Status.DRAFT;
	}

	private void require(Status expected) {
		if (status != expected) {
			throw new IllegalStateException("Lig " + id + " durumu " + status + ", beklenen " + expected);
		}
	}

	public Standings.Points points() {
		return new Standings.Points(pointsWin, pointsDraw, pointsLoss);
	}

	public Long getId() {
		return id;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public String getName() {
		return name;
	}

	public Format getFormat() {
		return format;
	}

	public boolean isDoubleRound() {
		return doubleRound;
	}

	public int getPointsWin() {
		return pointsWin;
	}

	public int getPointsDraw() {
		return pointsDraw;
	}

	public int getPointsLoss() {
		return pointsLoss;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

}
