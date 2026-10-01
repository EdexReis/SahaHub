package com.sahahub.business.domain;

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

/**
 * Sahanın belirli bir süre kapatılması (bakım, etkinlik, işletme kararı).
 * Rezervasyonlar gibi pitch_occupancy tablosuna kayıt düşer; böylece aynı çakışma
 * kısıtına tabidir.
 */
@Entity
@Table(name = "pitch_block")
public class PitchBlock {

	public enum Reason {

		MAINTENANCE("Bakım"), EVENT("Özel etkinlik"), MANAGEMENT("İşletme kararı");

		private final String label;

		Reason(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pitch_id", nullable = false)
	private Long pitchId;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Reason reason;

	private String note;

	@Column(name = "created_by", nullable = false)
	private Long createdBy;

	@Column(nullable = false)
	private boolean cancelled;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PitchBlock() {
	}

	public PitchBlock(Long pitchId, TimeRange range, Reason reason, String note, Long createdBy, Instant createdAt) {
		this.pitchId = pitchId;
		this.startsAt = range.start();
		this.endsAt = range.end();
		this.reason = reason;
		this.note = note;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	public void cancel() {
		this.cancelled = true;
	}

	public TimeRange range() {
		return new TimeRange(startsAt, endsAt);
	}

	public Long getId() {
		return id;
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

	public Reason getReason() {
		return reason;
	}

	public String getNote() {
		return note;
	}

	public boolean isCancelled() {
		return cancelled;
	}

}
