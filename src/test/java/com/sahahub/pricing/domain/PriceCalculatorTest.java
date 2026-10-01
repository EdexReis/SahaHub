package com.sahahub.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sahahub.shared.domain.TimeRange;

class PriceCalculatorTest {

	static final ZoneId IST = ZoneId.of("Europe/Istanbul");
	static final BigDecimal BASE = new BigDecimal("1000.00");
	static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);
	static final LocalDate SATURDAY = LocalDate.of(2026, 3, 7);

	static TimeRange range(LocalDate day, int h, int m, int minutes) {
		var start = day.atTime(h, m).atZone(IST).toInstant();
		return new TimeRange(start, start.plusSeconds(minutes * 60L));
	}

	static PriceRule rule(String name, EnumSet<DayOfWeek> days, int fromHour, Integer toHour, String price,
			int priority) {
		return new PriceRule(1L, name, days, LocalTime.of(fromHour, 0), toHour == null ? null : LocalTime.of(toHour, 0),
				new BigDecimal(price), priority);
	}

	@Test
	void noRuleUsesBasePrice() {
		PriceQuote q = PriceCalculator.quote(range(TUESDAY, 14, 0, 60), IST, BASE, "TRY", List.of());
		assertThat(q.total()).isEqualByComparingTo("1000.00");
		assertThat(q.lines()).singleElement().extracting(PriceQuote.Line::label).isEqualTo("Standart ücret");
	}

	@Test
	void bookingCrossingRuleBoundaryIsSplitByMinutes() {
		var evening = rule("Akşam", EnumSet.allOf(DayOfWeek.class), 18, null, "1600.00", 10);
		PriceQuote q = PriceCalculator.quote(range(TUESDAY, 17, 30, 60), IST, BASE, "TRY", List.of(evening));
		assertThat(q.lines()).hasSize(2);
		assertThat(q.lines().get(0).minutes()).isEqualTo(30);
		assertThat(q.lines().get(0).amount()).isEqualByComparingTo("500.00");
		assertThat(q.lines().get(1).label()).isEqualTo("Akşam");
		assertThat(q.lines().get(1).amount()).isEqualByComparingTo("800.00");
		assertThat(q.total()).isEqualByComparingTo("1300.00");
	}

	@Test
	void higherPriorityWins_whenRulesOverlap() {
		var weekend = rule("Hafta sonu", EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 0, null, "1500.00", 5);
		var weekendEvening = rule("Hafta sonu akşam", EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 18, null,
				"2000.00", 10);
		PriceQuote q = PriceCalculator.quote(range(SATURDAY, 20, 0, 60), IST, BASE, "TRY",
				List.of(weekend, weekendEvening));
		assertThat(q.total()).isEqualByComparingTo("2000.00");
		PriceQuote day = PriceCalculator.quote(range(SATURDAY, 12, 0, 60), IST, BASE, "TRY",
				List.of(weekend, weekendEvening));
		assertThat(day.total()).isEqualByComparingTo("1500.00");
	}

	@Test
	void dateLimitedRuleAppliesOnlyInsideItsRange() {
		var special = rule("Bayram", EnumSet.allOf(DayOfWeek.class), 0, null, "2500.00", 50);
		special.limitToDates(TUESDAY, TUESDAY);
		assertThat(PriceCalculator.quote(range(TUESDAY, 14, 0, 60), IST, BASE, "TRY", List.of(special)).total())
			.isEqualByComparingTo("2500.00");
		assertThat(PriceCalculator.quote(range(TUESDAY.plusDays(1), 14, 0, 60), IST, BASE, "TRY", List.of(special))
			.total()).isEqualByComparingTo("1000.00");
	}

	@Test
	void midnightCrossingUsesNextDaysRules() {
		// Cuma 23:30 - Cumartesi 00:30: ikinci yarı hafta sonu tarifesiyle
		var weekend = rule("Hafta sonu", EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 0, null, "1500.00", 5);
		PriceQuote q = PriceCalculator.quote(range(SATURDAY.minusDays(1), 23, 30, 60), IST, BASE, "TRY",
				List.of(weekend));
		assertThat(q.lines()).extracting(PriceQuote.Line::amount)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(new BigDecimal("500.00"), new BigDecimal("750.00"));
	}

	@Test
	void roundingIsHalfUpPerLine() {
		// 1000/60 × 50 dk = 833,333.. → 833,33 ; 45 dk × 999,99/60 = 749,9925 → 749,99
		assertThat(PriceCalculator.quote(range(TUESDAY, 14, 0, 50), IST, BASE, "TRY", List.of()).total())
			.isEqualByComparingTo("833.33");
		assertThat(PriceCalculator.quote(range(TUESDAY, 14, 0, 45), IST, new BigDecimal("999.99"), "TRY", List.of())
			.total()).isEqualByComparingTo("749.99");
		// 70 dk × 1000/60 = 1166,666.. → 1166,67 (yukarı)
		assertThat(PriceCalculator.quote(range(TUESDAY, 14, 0, 70), IST, BASE, "TRY", List.of()).total())
			.isEqualByComparingTo("1166.67");
	}

}
