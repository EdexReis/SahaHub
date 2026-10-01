package com.sahahub.business.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import com.sahahub.shared.domain.TimeRange;

/**
 * Bir şubenin çalışma takvimi: haftalık plan + tatil/özel gün istisnaları.
 * Veritabanına veya Spring'e bağımlı değildir; birim testleriyle doğrudan denenir.
 * <p>
 * "İş günü" kavramı: 1 Ekim için açılış 10:00, kapanış 02:00 ise 1 Ekim iş gününün
 * penceresi 1 Ekim 10:00 – 2 Ekim 02:00'dir. Gece 00:30'daki maç 1 Ekim iş gününe aittir.
 */
public record BranchSchedule(ZoneId zone, Map<DayOfWeek, DayHours> weekly, Map<LocalDate, DayHours> specialDays) {

	public BranchSchedule {
		weekly = Map.copyOf(weekly);
		specialDays = Map.copyOf(specialDays);
	}

	/** Verilen iş gününün açık olduğu zaman aralığı; kapalıysa boş. */
	public Optional<TimeRange> windowFor(LocalDate businessDay) {
		DayHours hours = specialDays.getOrDefault(businessDay,
				weekly.getOrDefault(businessDay.getDayOfWeek(), DayHours.CLOSED));
		if (hours.closed()) {
			return Optional.empty();
		}
		ZonedDateTime open = businessDay.atTime(hours.open()).atZone(zone);
		LocalDate closeDay = hours.crossesMidnight() ? businessDay.plusDays(1) : businessDay;
		ZonedDateTime close = closeDay.atTime(hours.close()).atZone(zone);
		return Optional.of(new TimeRange(open.toInstant(), close.toInstant()));
	}

	/**
	 * Aralık tamamen tek bir iş gününün açık penceresinde mi?
	 * Gece yarısını aşan pencereler için bir önceki günün penceresine de bakılır.
	 */
	public boolean isOpenDuring(TimeRange range) {
		return businessDayOf(range).isPresent();
	}

	/** Aralığı kapsayan iş günü (varsa). */
	public Optional<LocalDate> businessDayOf(TimeRange range) {
		LocalDate localDate = range.start().atZone(zone).toLocalDate();
		for (LocalDate candidate : new LocalDate[] { localDate, localDate.minusDays(1) }) {
			Optional<TimeRange> window = windowFor(candidate);
			if (window.isPresent() && window.get().contains(range)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	public LocalDate today(Instant now) {
		return now.atZone(zone).toLocalDate();
	}

}
