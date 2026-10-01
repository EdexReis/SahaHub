package com.sahahub.business.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sahahub.shared.domain.TimeRange;

class BranchScheduleTest {

	static final ZoneId IST = ZoneId.of("Europe/Istanbul");
	static final LocalDate TUE = LocalDate.of(2026, 3, 3);

	static BranchSchedule schedule(Map<LocalDate, DayHours> special) {
		Map<DayOfWeek, DayHours> weekly = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek d : DayOfWeek.values()) {
			weekly.put(d, DayHours.open(LocalTime.of(10, 0), LocalTime.of(2, 0)));
		}
		weekly.put(DayOfWeek.MONDAY, DayHours.CLOSED);
		return new BranchSchedule(IST, weekly, special);
	}

	static TimeRange range(LocalDate d, int h, int minutes) {
		var s = d.atTime(h, 0).atZone(IST).toInstant();
		return new TimeRange(s, s.plus(Duration.ofMinutes(minutes)));
	}

	@Test
	void windowCrossesMidnight() {
		TimeRange w = schedule(Map.of()).windowFor(TUE).orElseThrow();
		assertThat(w.start()).isEqualTo(TUE.atTime(10, 0).atZone(IST).toInstant());
		assertThat(w.end()).isEqualTo(TUE.plusDays(1).atTime(2, 0).atZone(IST).toInstant());
	}

	@Test
	void afterMidnightBookingBelongsToPreviousDay() {
		BranchSchedule s = schedule(Map.of());
		assertThat(s.businessDayOf(range(TUE.plusDays(1), 1, 60))).contains(TUE);
		assertThat(s.isOpenDuring(range(TUE.plusDays(1), 1, 90))).isFalse(); // 01:00-02:30 kapanışı aşar
	}

	@Test
	void closedWeekdayAndHolidayOverride() {
		LocalDate monday = LocalDate.of(2026, 3, 2);
		BranchSchedule s = schedule(Map.of(TUE, DayHours.CLOSED, monday,
				DayHours.open(LocalTime.of(12, 0), LocalTime.of(18, 0))));
		assertThat(s.windowFor(TUE)).isEmpty();
		assertThat(s.isOpenDuring(range(monday, 13, 60))).isTrue(); // normalde kapalı pazartesi, özel gün açık
		assertThat(s.isOpenDuring(range(monday, 18, 60))).isFalse();
	}

	@Test
	void earlyMorningOfDayAfterClosedDayIsClosed() {
		// Pazartesi kapalı olduğu için Salı 01:00 açık değildir (Pazartesi penceresi yok)
		BranchSchedule s = schedule(Map.of());
		assertThat(s.isOpenDuring(range(TUE, 1, 60))).isFalse();
	}

}
