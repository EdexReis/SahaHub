package com.sahahub.booking.domain;

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
 * Sahayı meşgul eden her şeyin ortak kaydı. Veritabanındaki EXCLUDE kısıtı sayesinde
 * aynı sahada aktif iki kaydın aralıkları kesişemez — iki istek aynı milisaniyede gelse bile.
 */
@Entity
@Table(name = "pitch_occupancy")
public class PitchOccupancy {

	public enum Source {
		RESERVATION, BLOCK, TOURNAMENT_MATCH
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
	@Column(name = "source_type", nullable = false)
	private Source sourceType;

	@Column(name = "source_id", nullable = false)
	private Long sourceId;

	@Column(nullable = false)
	private boolean active = true;

	protected PitchOccupancy() {
	}

	public PitchOccupancy(Long pitchId, TimeRange range, Source sourceType, Long sourceId) {
		this.pitchId = pitchId;
		this.startsAt = range.start();
		this.endsAt = range.end();
		this.sourceType = sourceType;
		this.sourceId = sourceId;
	}

	public TimeRange range() {
		return new TimeRange(startsAt, endsAt);
	}

	public Long getPitchId() {
		return pitchId;
	}

	public Source getSourceType() {
		return sourceType;
	}

	public Long getSourceId() {
		return sourceId;
	}

	public boolean isActive() {
		return active;
	}

}
