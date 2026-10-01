package com.sahahub.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.DayHours;
import com.sahahub.reporting.domain.ReportCalculator.Booking;
import com.sahahub.reporting.domain.ReportCalculator.CollectionRow;
import com.sahahub.reporting.domain.ReportCalculator.Grouping;
import com.sahahub.reporting.domain.ReportCalculator.Interval;
import com.sahahub.reporting.domain.ReportCalculator.Method;
import com.sahahub.reporting.domain.ReportCalculator.Movement;
import com.sahahub.reporting.domain.ReportCalculator.Period;
import com.sahahub.reporting.domain.ReportCalculator.PitchUsage;

/** Rapor metriklerinin tanımları (docs/RAPORLAR.md ile aynı). */
class ReportCalculatorTest {

	static final ZoneId IST = ZoneId.of("Europe/Istanbul");
	static final LocalDate MON = LocalDate.of(2026, 3, 2);

	static Instant at(LocalDate d, int h, int m) {
		return d.atTime(h, m).atZone(IST).toInstant();
	}

	static BigDecimal tl(String s) {
		return new BigDecimal(s);
	}

	static Booking booking(long id, long pitch, Long customer, String status, LocalDate day, int hour, int minutes,
			int buffer, String total, boolean confirmed, Long cancelledBy) {
		Instant s = at(day, hour, 0);
		return new Booking(id, pitch, customer, customer == null ? null : "Müşteri " + customer, status, s,
				s.plusSeconds(60L * minutes), buffer, tl(total), confirmed ? s.minusSeconds(86400) : null, cancelledBy,
				hour < 6 ? day.minusDays(1) : day);
	}

	/** Her gün 09:00-01:00 açık (16 saat = 960 dk). */
	static BranchSchedule openDaily() {
		Map<DayOfWeek, DayHours> w = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek d : DayOfWeek.values()) {
			w.put(d, DayHours.open(LocalTime.of(9, 0), LocalTime.of(1, 0)));
		}
		return new BranchSchedule(IST, w, Map.of());
	}

	@Test
	void periodLimits() {
		assertThatThrownBy(() -> new Period(MON, MON.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Period(MON, MON.plusDays(366))).isInstanceOf(IllegalArgumentException.class);
		assertThat(new Period(MON, MON.plusDays(365)).dayCount()).isEqualTo(366);
	}

	@Test
	void collectionsGroupByPaymentDate_netSubtractsRefundsAndReversals() {
		List<Movement> m = List.of(new Movement(1, "CHARGE", Method.CASH, tl("500"), at(MON, 20, 0)),
				new Movement(1, "CHARGE", Method.ONLINE_SIM, tl("300"), at(MON.plusDays(1), 10, 0)),
				new Movement(2, "REFUND", Method.ONLINE_SIM, tl("100"), at(MON.plusDays(1), 11, 0)),
				new Movement(3, "REVERSAL", Method.CASH, tl("50"), at(MON.plusDays(8), 9, 0)),
				// gece 00:30: ödeme takvim gününe (Salı) yazılır, iş gününe değil
				new Movement(4, "CHARGE", Method.MANUAL_POS, tl("200"), at(MON.plusDays(2), 0, 30)),
				// aralık dışı
				new Movement(5, "CHARGE", Method.CASH, tl("999"), at(MON.minusDays(1), 12, 0)));
		Period p = new Period(MON, MON.plusDays(9)); // Pzt 2 Mart – Çar 11 Mart

		List<CollectionRow> daily = ReportCalculator.collections(m, p, Grouping.DAY, IST);
		assertThat(daily).hasSize(10);
		assertThat(daily.get(0).cash()).isEqualByComparingTo("500");
		assertThat(daily.get(1).net()).isEqualByComparingTo("200"); // 300 − 100
		assertThat(daily.get(2).pos()).isEqualByComparingTo("200");
		assertThat(daily.get(5).net()).isEqualByComparingTo("0"); // boş gün de satır

		List<CollectionRow> weekly = ReportCalculator.collections(m, p, Grouping.WEEK, IST);
		assertThat(weekly).hasSize(2);
		assertThat(weekly.get(0).net()).isEqualByComparingTo("900");
		assertThat(weekly.get(1).start()).isEqualTo(MON.plusDays(7));
		assertThat(weekly.get(1).end()).isEqualTo(MON.plusDays(9)); // aralık sonunda kırpılır
		assertThat(weekly.get(1).net()).isEqualByComparingTo("-50");

		CollectionRow total = ReportCalculator.total(weekly);
		assertThat(total.charges()).isEqualByComparingTo("1000");
		assertThat(total.net()).isEqualByComparingTo("850");

		List<CollectionRow> monthly = ReportCalculator.collections(m, new Period(MON.plusDays(10), MON.plusDays(40)),
				Grouping.MONTH, IST);
		assertThat(monthly).extracting(CollectionRow::start).containsExactly(MON.plusDays(10), LocalDate.of(2026, 4, 1));
	}

	@Test
	void receivablesSeparateBookedCollectedOutstandingAndRefundDue() {
		List<Booking> b = List.of(booking(1, 1, 10L, "CONFIRMED", MON, 20, 60, 0, "1000", true, null),
				booking(2, 1, 11L, "COMPLETED", MON, 21, 60, 0, "1000", true, null),
				booking(3, 1, 12L, "CANCELLED", MON, 22, 60, 0, "800", true, 12L),
				booking(4, 1, null, "NO_SHOW", MON, 19, 60, 0, "500", true, null));
		Map<Long, BigDecimal> paid = Map.of(1L, tl("300"), 2L, tl("1200"), 3L, tl("240"));

		ReportCalculator.Receivables r = ReportCalculator.receivables(b, paid);
		assertThat(r.count()).isEqualTo(3);
		assertThat(r.booked()).isEqualByComparingTo("2500"); // iptal edilen bedele girmez
		assertThat(r.collected()).isEqualByComparingTo("1500");
		assertThat(r.outstanding()).isEqualByComparingTo("1200"); // 700 + 0 + 500
		assertThat(r.refundDue()).isEqualByComparingTo("440"); // 200 fazla ödeme + iptalde kalan 240
	}

	@Test
	void occupancyDenominatorExcludesClosedTimeAndBlocks_bufferShownSeparately() {
		Map<Long, String> pitches = new LinkedHashMap<>();
		pitches.put(1L, "A");
		pitches.put(2L, "B");
		Period p = new Period(MON, MON); // 960 dk açık
		List<Booking> b = List.of(booking(1, 1, 10L, "CONFIRMED", MON, 20, 60, 15, "1000", true, null),
				booking(2, 1, 11L, "COMPLETED", MON.plusDays(1), 0, 60, 15, "900", true, null), // gece: Pazartesi iş günü
				booking(3, 1, 12L, "CANCELLED", MON, 18, 60, 15, "1000", true, 12L), // sayılmaz
				booking(4, 1, 13L, "HELD", MON, 17, 60, 15, "1000", false, null)); // sayılmaz
		List<Interval> blocks = List.of(new Interval(1, at(MON, 7, 0), at(MON, 11, 0))); // 2 saati pencere içinde
		List<Interval> matches = List.of(new Interval(2, at(MON, 21, 0), at(MON, 22, 30)));

		List<PitchUsage> u = ReportCalculator.usage(pitches, openDaily(), p, b, blocks, matches);
		PitchUsage a = u.get(0);
		assertThat(a.open()).isEqualTo(960);
		assertThat(a.blocked()).isEqualTo(120);
		assertThat(a.sellable()).isEqualTo(840);
		assertThat(a.reserved()).isEqualTo(120);
		assertThat(a.buffer()).isEqualTo(30);
		assertThat(a.occupancy()).isEqualByComparingTo("14.3"); // 120 / 840
		assertThat(a.busy()).isEqualByComparingTo("17.9"); // 150 / 840
		assertThat(a.booked()).isEqualByComparingTo("1900");
		PitchUsage bb = u.get(1);
		assertThat(bb.match()).isEqualTo(90);
		assertThat(bb.occupancy()).isEqualByComparingTo("9.4"); // 90 / 960

		PitchUsage total = ReportCalculator.totalUsage(u);
		assertThat(total.sellable()).isEqualTo(1800);
		assertThat(total.occupancy()).isEqualByComparingTo("11.7"); // 210 / 1800
	}

	@Test
	void closedDaysAreNotInTheDenominator() {
		Map<DayOfWeek, DayHours> w = new EnumMap<>(DayOfWeek.class);
		w.put(DayOfWeek.MONDAY, DayHours.open(LocalTime.of(10, 0), LocalTime.of(12, 0)));
		BranchSchedule s = new BranchSchedule(IST, w, Map.of(MON.plusDays(7), DayHours.CLOSED));
		List<PitchUsage> u = ReportCalculator.usage(Map.of(1L, "A"), s, new Period(MON, MON.plusDays(13)), List.of(),
				List.of(), List.of());
		assertThat(u.getFirst().open()).isEqualTo(120); // yalnızca ilk pazartesi; ikincisi özel kapalı gün
		assertThat(ReportCalculator.usage(Map.of(1L, "A"), s, new Period(MON.plusDays(1), MON.plusDays(1)), List.of(),
				List.of(), List.of()).getFirst().occupancy()).isNull();
	}

	@Test
	void ratesSeparateDroppedHoldsFromCancellations() {
		Instant now = at(MON.plusDays(2), 12, 0);
		List<Booking> b = List.of(booking(1, 1, 10L, "CANCELLED", MON, 20, 60, 0, "1", true, 10L), // müşteri iptali
				booking(2, 1, 11L, "CANCELLED", MON, 21, 60, 0, "1", true, 99L), // şube iptali
				booking(3, 1, 12L, "CANCELLED", MON, 22, 60, 0, "1", false, 12L), // tutmada bırakıldı
				booking(4, 1, 13L, "EXPIRED", MON, 18, 60, 0, "1", false, null),
				booking(5, 1, 14L, "COMPLETED", MON, 17, 60, 0, "1", true, null),
				booking(6, 1, null, "NO_SHOW", MON, 16, 60, 0, "1", true, null),
				booking(7, 1, 15L, "COMPLETED", MON, 15, 60, 0, "1", true, null),
				booking(8, 1, 16L, "CONFIRMED", MON, 14, 60, 0, "1", true, null), // geçmiş, işaretlenmemiş
				booking(9, 1, 17L, "CONFIRMED", MON.plusDays(5), 20, 60, 0, "1", true, null)); // aralık dışı
		ReportCalculator.Rates r = ReportCalculator.rates(b, new Period(MON, MON.plusDays(1)), now);
		assertThat(r.confirmed()).isEqualTo(6);
		assertThat(r.cancelledByCustomer()).isEqualTo(1);
		assertThat(r.cancelledByStaff()).isEqualTo(1);
		assertThat(r.droppedHolds()).isEqualTo(2);
		assertThat(r.cancelRate()).isEqualByComparingTo("33.3"); // 2 / 6
		assertThat(r.noShowRate()).isEqualByComparingTo("33.3"); // 1 / 3
		assertThat(r.unmarkedPast()).isEqualTo(1);
	}

	@Test
	void repeatCustomersAndHeatmapUseBusinessDay() {
		List<Booking> b = List.of(booking(1, 1, 10L, "COMPLETED", MON, 20, 60, 0, "1", true, null),
				booking(2, 1, 10L, "CONFIRMED", MON.plusDays(3), 20, 60, 0, "1", true, null),
				booking(3, 1, 11L, "COMPLETED", MON, 21, 60, 0, "1", true, null),
				booking(4, 1, 11L, "CANCELLED", MON.plusDays(1), 21, 60, 0, "1", true, 11L), // sayılmaz
				booking(5, 1, null, "COMPLETED", MON, 22, 60, 0, "1", true, null), // misafir
				booking(6, 1, 12L, "COMPLETED", MON.plusDays(2), 0, 60, 0, "1", true, null)); // Salı iş günü, 00:00
		Period p = new Period(MON, MON.plusDays(6));
		ReportCalculator.Repeat r = ReportCalculator.repeatCustomers(b, p, 10);
		assertThat(r.distinct()).isEqualTo(3);
		assertThat(r.repeat()).isEqualTo(1);
		assertThat(r.guestBookings()).isEqualTo(1);
		assertThat(r.top()).singleElement().satisfies(v -> {
			assertThat(v.customerId()).isEqualTo(10L);
			assertThat(v.visits()).isEqualTo(2);
			assertThat(v.last()).isEqualTo(MON.plusDays(3));
		});

		ReportCalculator.Heatmap h = ReportCalculator.peakHours(b, p, IST);
		assertThat(h.hours()).containsExactly(20, 21, 22, 23, 0); // gece saati en sonda
		assertThat(h.count(DayOfWeek.MONDAY, 0)).isEqualTo(1);
		assertThat(h.count(DayOfWeek.TUESDAY, 4)).isEqualTo(1); // 00:00 maçı Salı satırında
		assertThat(h.count(DayOfWeek.THURSDAY, 0)).isEqualTo(1);
		assertThat(h.level(DayOfWeek.MONDAY, 0)).isEqualTo(4);
		assertThat(h.level(DayOfWeek.FRIDAY, 0)).isZero();
	}

	@Test
	void expensesSubtractReversals() {
		List<ReportCalculator.ExpenseItem> e = List.of(new ReportCalculator.ExpenseItem("Malzeme", tl("250"), at(MON, 10, 0)),
				new ReportCalculator.ExpenseItem("Malzeme", tl("-250"), at(MON, 11, 0)),
				new ReportCalculator.ExpenseItem("Elektrik", tl("1200"), at(MON, 12, 0)));
		ReportCalculator.ExpenseSummary s = ReportCalculator.expenses(e, new Period(MON, MON), IST);
		assertThat(s.total()).isEqualByComparingTo("1200");
		assertThat(s.byCategory().get("Malzeme")).isEqualByComparingTo("0");
	}

}
