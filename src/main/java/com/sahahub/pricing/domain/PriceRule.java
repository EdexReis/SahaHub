package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Bir sahanın belirli gün/saat dilimindeki saatlik ücreti (ör. "Hafta içi akşam").
 * Saatler şubenin yerel saatidir. endTime boşsa kural gece yarısına (24:00) kadar geçerlidir.
 */
@Entity
@Table(name = "price_rule")
public class PriceRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pitch_id", nullable = false)
	private Long pitchId;

	@Column(nullable = false)
	private String name;

	/** bit0 = Pazartesi ... bit6 = Pazar */
	@Column(name = "days_mask", nullable = false)
	private short daysMask;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

	@Column(name = "valid_from")
	private LocalDate validFrom;

	@Column(name = "valid_to")
	private LocalDate validTo;

	@Column(name = "hourly_price", nullable = false)
	private BigDecimal hourlyPrice;

	@Column(nullable = false)
	private int priority;

	@Column(nullable = false)
	private boolean active = true;

	protected PriceRule() {
	}

	public PriceRule(Long pitchId, String name, Set<DayOfWeek> days, LocalTime startTime, LocalTime endTime,
			BigDecimal hourlyPrice, int priority) {
		this.pitchId = pitchId;
		this.name = name;
		this.daysMask = maskOf(days);
		this.startTime = startTime;
		this.endTime = endTime;
		this.hourlyPrice = hourlyPrice;
		this.priority = priority;
	}

	public static short maskOf(Set<DayOfWeek> days) {
		int mask = 0;
		for (DayOfWeek d : days) {
			mask |= 1 << (d.getValue() - 1);
		}
		return (short) mask;
	}

	public void limitToDates(LocalDate from, LocalDate to) {
		this.validFrom = from;
		this.validTo = to;
	}

	public void changePrice(BigDecimal hourlyPrice) {
		this.hourlyPrice = hourlyPrice;
	}

	public void deactivate() {
		this.active = false;
	}

	/** Bu kural, yerel saatte verilen dakikaya uygulanır mı? */
	public boolean appliesAt(LocalDateTime minute) {
		if (!active) {
			return false;
		}
		LocalDate date = minute.toLocalDate();
		if ((daysMask & (1 << (date.getDayOfWeek().getValue() - 1))) == 0) {
			return false;
		}
		if (validFrom != null && date.isBefore(validFrom)) {
			return false;
		}
		if (validTo != null && date.isAfter(validTo)) {
			return false;
		}
		LocalTime t = minute.toLocalTime();
		return !t.isBefore(startTime) && (endTime == null || t.isBefore(endTime));
	}

	public Long getId() {
		return id;
	}

	public Long getPitchId() {
		return pitchId;
	}

	public String getName() {
		return name;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}

	public LocalDate getValidFrom() {
		return validFrom;
	}

	public LocalDate getValidTo() {
		return validTo;
	}

	/** Seçili günler (Pazartesi'den başlayarak). */
	public java.util.List<DayOfWeek> days() {
		java.util.List<DayOfWeek> list = new java.util.ArrayList<>();
		for (DayOfWeek d : DayOfWeek.values()) {
			if ((daysMask & (1 << (d.getValue() - 1))) != 0) {
				list.add(d);
			}
		}
		return list;
	}

	public BigDecimal getHourlyPrice() {
		return hourlyPrice;
	}

	public int getPriority() {
		return priority;
	}

	public boolean isActive() {
		return active;
	}

}
