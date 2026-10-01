package com.sahahub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.HoldExpiryService;
import com.sahahub.booking.app.PitchBlockService;
import com.sahahub.booking.app.ReservationView;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.HoldExpiredException;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.domain.PriceRule;
import com.sahahub.pricing.domain.PriceRuleRepository;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Rezervasyon motorunun kritik kuralları, gerçek PostgreSQL üzerinde. */
@IntegrationTest
class ReservationRulesIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3); // Salı

	@Autowired
	CustomerBookingService customer;

	@Autowired
	StaffReservationService staff;

	@Autowired
	PitchBlockService blocks;

	@Autowired
	HoldExpiryService expiry;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	PitchRepository pitches;

	@Autowired
	PriceRuleRepository priceRules;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	static Instant at(LocalDate day, int hour, int minute) {
		return day.atTime(hour, minute).atZone(TestData.IST).toInstant();
	}

	String staffBook(Venue v, LocalDateTime start, int minutes) {
		return staffBook(v, v.pitch().getId(), start, minutes);
	}

	String staffBook(Venue v, Long pitchId, LocalDateTime start, int minutes) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(pitchId,
				start, minutes, Channel.PHONE, null, "Misafir", "0555 111 22 33", null));
	}

	// ---------------------------------------------------------------- 2. kısmi çakışma

	@Nested
	class Overlaps {

		@Test
		void partialOverlapIsRejected_adjacentIsAllowed() {
			Venue v = data.venue();
			staffBook(v, DAY.atTime(20, 0), 90); // 20:00-21:30

			// 21:00-22:00, 20:00-21:30 ile 30 dk kesişir
			assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0)))
				.isInstanceOf(SlotUnavailableException.class);
			// 19:00-20:00 bitişik: [19:00, 20:00) ile [20:00, 21:30) kesişmez
			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 19, 0))).isNotBlank();
			// İçeride kalan aralık da reddedilir
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(20, 15), 30))
				.isInstanceOf(SlotUnavailableException.class);
			// Tamamen kapsayan aralık da reddedilir
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(19, 45), 120))
				.isInstanceOf(SlotUnavailableException.class);
		}

		@Test
		void sameTimeOnAnotherPitchIsFine() {
			Venue v = data.venue();
			var other = data.secondPitch(v);
			staffBook(v, DAY.atTime(20, 0), 60);
			assertThat(staffBook(v, other.getId(), DAY.atTime(20, 0), 60)).isNotBlank();
		}

		@Test
		void databaseRejectsOverlapEvenWhenApplicationIsBypassed() {
			Venue v = data.venue();
			Long pitchId = v.pitch().getId();
			jdbc.update("insert into pitch_occupancy (pitch_id, starts_at, ends_at, source_type, source_id) "
					+ "values (?, '2026-03-03 17:00Z', '2026-03-03 18:00Z', 'BLOCK', -1)", pitchId);
			assertThatThrownBy(() -> jdbc.update("insert into pitch_occupancy (pitch_id, starts_at, ends_at, "
					+ "source_type, source_id) values (?, '2026-03-03 17:30Z', '2026-03-03 18:30Z', 'BLOCK', -2)",
					pitchId))
				.hasMessageContaining("ex_pitch_occupancy_no_overlap");
			// Pasif (active=false) kayıt çakışma yaratmaz
			jdbc.update("insert into pitch_occupancy (pitch_id, starts_at, ends_at, source_type, source_id, active) "
					+ "values (?, '2026-03-03 17:30Z', '2026-03-03 18:30Z', 'BLOCK', -3, false)", pitchId);
		}

	}

	// ---------------------------------------------------------------- 3. bakım ve hazırlık süresi

	@Nested
	class BufferAndMaintenance {

		@Test
		void bufferBlocksTheNextStartUntilItEnds() {
			Venue v = data.venue(15, 60, 15); // 15 dk hazırlık
			staffBook(v, DAY.atTime(20, 0), 60); // sahayı 20:00-21:15 meşgul eder

			assertThatThrownBy(() -> staffBook(v, DAY.atTime(21, 0), 60)).isInstanceOf(SlotUnavailableException.class);
			assertThat(staffBook(v, DAY.atTime(21, 15), 60)).isNotBlank();
			// Önceki maç da kendi hazırlık süresi kadar boşluk bırakmalı: 18:45-19:45 + 15 = 20:00 → olur
			assertThat(staffBook(v, DAY.atTime(18, 45), 60)).isNotBlank();
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(17, 0), 120)) // 17:00-19:00+15, 18:45 ile çakışır
				.isInstanceOf(SlotUnavailableException.class);
		}

		@Test
		void maintenanceBlockPreventsBookings_andCannotCoverExistingBooking() {
			Venue v = data.venue();
			blocks.create(v.manager(), v.pitch().getId(), DAY.atTime(14, 0), DAY.atTime(17, 0),
					PitchBlock.Reason.MAINTENANCE, "Çim bakımı");

			assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(DAY, 16, 0)))
				.isInstanceOf(SlotUnavailableException.class);
			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 17, 0))).isNotBlank();

			staffBook(v, DAY.atTime(20, 0), 60);
			assertThatThrownBy(() -> blocks.create(v.manager(), v.pitch().getId(), DAY.atTime(19, 30),
					DAY.atTime(20, 30), PitchBlock.Reason.EVENT, null))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("rezervasyon");
		}

		@Test
		void cancelledBlockFreesTheTime() {
			Venue v = data.venue();
			Long blockId = blocks.create(v.manager(), v.pitch().getId(), DAY.atTime(14, 0), DAY.atTime(17, 0),
					PitchBlock.Reason.MAINTENANCE, null);
			blocks.cancel(v.manager(), blockId);
			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 15, 0))).isNotBlank();
		}

	}

	// ---------------------------------------------------------------- 4. gece yarısı

	@Nested
	class Midnight {

		@Test
		void slotAfterMidnightBelongsToPreviousBusinessDay() {
			Venue v = data.venue(); // 09:00 - 01:00
			// 3 Mart iş gününün son saati: 4 Mart 00:00 - 01:00
			String code = customer.hold(data.customer(), v.pitch().getId(), at(DAY.plusDays(1), 0, 0));
			ReservationView r = staff.view(v.owner(), code);
			assertThat(r.start().toLocalDateTime()).isEqualTo(DAY.plusDays(1).atTime(0, 0));
			assertThat(r.end().toLocalDateTime()).isEqualTo(DAY.plusDays(1).atTime(1, 0));
			// Personel takvimine dönüş bu iş gününe yapılmalı (ertesi takvim gününe değil)
			assertThat(r.businessDay()).isEqualTo(DAY);
		}

		@Test
		void bookingSpanningMidnightIsStoredAndPricedPerSide() {
			Venue v = data.venue();
			tx(() -> priceRules.save(new PriceRule(v.pitch().getId(), "Gece", java.util.EnumSet
				.allOf(java.time.DayOfWeek.class), java.time.LocalTime.of(0, 0), java.time.LocalTime.of(2, 0),
					new BigDecimal("600.00"), 10)));
			String code = staffBook(v, DAY.atTime(23, 30), 60); // 23:30 - 00:30
			ReservationView r = staff.view(v.owner(), code);
			assertThat(r.lines()).hasSize(2);
			assertThat(r.lines().get(0).amount()).isEqualByComparingTo("500.00"); // 30 dk × 1000
			assertThat(r.lines().get(1).amount()).isEqualByComparingTo("300.00"); // 30 dk × 600
			assertThat(r.total()).isEqualByComparingTo("800.00");
		}

		@Test
		void bookingPastClosingIsRejected() {
			Venue v = data.venue();
			assertThatThrownBy(() -> staffBook(v, DAY.plusDays(1).atTime(0, 30), 60)) // 00:30-01:30, kapanış 01:00
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("kapalı");
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(7, 0), 60))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("kapalı");
		}

		@Test
		void bookingInThePastIsRejected() {
			Venue v = data.venue();
			clock.set(at(DAY, 20, 5));
			assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(DAY, 20, 0)))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("Geçmiş");
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(20, 0), 60))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("Geçmiş");
		}

	}

	// ---------------------------------------------------------------- 5. süresi dolan tutma

	@Nested
	class HoldExpiry {

		@Test
		void expiredHoldIsReleasedOnce_andSlotBecomesAvailable() {
			Venue v = data.venue();
			AppUserPrincipal first = data.customer();
			String code = customer.hold(first, v.pitch().getId(), at(DAY, 21, 0));

			clock.advance(Duration.ofMinutes(9));
			assertThat(expiry.expireDueHolds()).isZero(); // henüz dolmadı

			clock.advance(Duration.ofMinutes(1)); // tam 10. dakika: süre doldu
			assertThat(expiry.expireDueHolds()).isGreaterThanOrEqualTo(1);
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
			assertThat(expiry.expireDueHolds()).isZero(); // tekrar çalıştırmak bir şey değiştirmez

			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0))).isNotBlank();
		}

		@Test
		void confirmingAfterExpiryFails_andExpiryIsPersisted() {
			Venue v = data.venue();
			AppUserPrincipal c = data.customer();
			String code = customer.hold(c, v.pitch().getId(), at(DAY, 22, 0));
			clock.advance(Duration.ofMinutes(10));

			assertThatThrownBy(() -> customer.confirm(c, code)).isInstanceOf(HoldExpiredException.class);
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 22, 0))).isNotBlank();
		}

		@Test
		void confirmBeforeExpiryWorks() {
			Venue v = data.venue();
			AppUserPrincipal c = data.customer();
			String code = customer.hold(c, v.pitch().getId(), at(DAY, 22, 0));
			clock.advance(Duration.ofMinutes(9).plusSeconds(59));
			customer.confirm(c, code);
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		}

	}

	// ---------------------------------------------------------------- 6. başarısız taşıma

	@Nested
	class Move {

		@Test
		void failedMoveKeepsOriginalReservationAndOccupancy() {
			Venue v = data.venue();
			String a = staffBook(v, DAY.atTime(20, 0), 60);
			staffBook(v, DAY.atTime(21, 0), 60);

			assertThatThrownBy(() -> staff.move(v.reception(), a, v.pitch().getId(), DAY.atTime(21, 0)))
				.isInstanceOf(SlotUnavailableException.class);

			ReservationView after = staff.view(v.owner(), a);
			assertThat(after.start().toLocalDateTime()).isEqualTo(DAY.atTime(20, 0));
			assertThat(after.status()).isEqualTo(ReservationStatus.CONFIRMED);
			// Eski saat hâlâ dolu (doluluk kaydı geri alındı)
			assertThatThrownBy(() -> staffBook(v, DAY.atTime(20, 0), 60)).isInstanceOf(SlotUnavailableException.class);
		}

		@Test
		void successfulMoveFreesOldTime_andShiftIntoOwnOldRangeWorks() {
			Venue v = data.venue();
			String a = staffBook(v, DAY.atTime(20, 0), 60);
			// Kendi eski aralığıyla kesişen yere kaydırma: 20:30
			staff.move(v.reception(), a, v.pitch().getId(), DAY.atTime(20, 30));
			assertThat(staff.view(v.owner(), a).start().toLocalDateTime()).isEqualTo(DAY.atTime(20, 30));
			assertThat(staffBook(v, DAY.atTime(19, 0), 90)).isNotBlank(); // 19:00-20:30 artık boş
		}

		@Test
		void moveToAnotherPitchInSameBranch() {
			Venue v = data.venue();
			var other = data.secondPitch(v);
			String a = staffBook(v, DAY.atTime(20, 0), 60);
			staff.move(v.reception(), a, other.getId(), DAY.atTime(20, 0));
			assertThat(staff.view(v.owner(), a).pitchId()).isEqualTo(other.getId());
			assertThat(staffBook(v, DAY.atTime(20, 0), 60)).isNotBlank();
		}

	}

	// ---------------------------------------------------------------- 7. fiyat anlık görüntüsü

	@Test
	void changingTariffDoesNotChangeExistingReservation() {
		Venue v = data.venue();
		PriceRule evening = tx(() -> priceRules.save(new PriceRule(v.pitch().getId(), "Akşam",
				java.util.EnumSet.allOf(java.time.DayOfWeek.class), java.time.LocalTime.of(18, 0), null,
				new BigDecimal("1500.00"), 10)));
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(DAY, 20, 0));
		assertThat(customer.view(c, code).total()).isEqualByComparingTo("1500.00");

		tx(() -> {
			PriceRule r = priceRules.findById(evening.getId()).orElseThrow();
			r.changePrice(new BigDecimal("2500.00"));
			var p = pitches.findById(v.pitch().getId()).orElseThrow();
			p.changeBasePrice(new BigDecimal("3000.00"));
			return null;
		});

		ReservationView after = customer.view(c, code);
		assertThat(after.total()).isEqualByComparingTo("1500.00");
		assertThat(after.lines()).singleElement().satisfies(l -> {
			assertThat(l.hourlyRate()).isEqualByComparingTo("1500.00");
			assertThat(l.label()).isEqualTo("Akşam");
		});
		// Yeni rezervasyon yeni tarifeyle fiyatlanır
		String newCode = customer.hold(c, v.pitch().getId(), at(DAY, 21, 0));
		assertThat(customer.view(c, newCode).total()).isEqualByComparingTo("2500.00");
	}

	// ---------------------------------------------------------------- 16. iptal süresinin sınır anları

	@Nested
	class CancellationCutoff {

		@Test
		void customerCanCancelExactlyAtDeadline() {
			Venue v = data.venue(); // iptal sınırı 24 saat
			AppUserPrincipal c = data.customer();
			String code = customer.hold(c, v.pitch().getId(), at(DAY, 21, 0));
			customer.confirm(c, code);
			clock.set(at(DAY.minusDays(1), 21, 0)); // tam 24 saat önce
			customer.cancel(c, code);
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		}

		@Test
		void customerCannotCancelOneSecondAfterDeadline_butStaffCan() {
			Venue v = data.venue();
			AppUserPrincipal c = data.customer();
			String code = customer.hold(c, v.pitch().getId(), at(DAY, 21, 0));
			customer.confirm(c, code);
			clock.set(at(DAY.minusDays(1), 21, 0).plusSeconds(1));
			assertThatThrownBy(() -> customer.cancel(c, code))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("iptal süresi geçti");

			staff.cancel(v.reception(), code, "Müşteri aradı");
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
			// İptal edilen saat yeniden alınabilir
			assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0))).isNotBlank();
		}

		@Test
		void heldReservationCanAlwaysBeReleased() {
			Venue v = data.venue();
			AppUserPrincipal c = data.customer();
			clock.set(at(DAY, 20, 0));
			String code = customer.hold(c, v.pitch().getId(), at(DAY, 21, 0)); // 1 saat kala
			customer.cancel(c, code);
			assertThat(reservations.findByCode(code).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		}

	}

	// ---------------------------------------------------------------- yardımcı

	@Autowired
	org.springframework.transaction.support.TransactionTemplate txTemplate;

	<T> T tx(java.util.function.Supplier<T> work) {
		return txTemplate.execute(status -> work.get());
	}

	@Test
	void customerCannotHoardMoreThanThreeHolds() {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		for (int h : List.of(10, 11, 12)) {
			customer.hold(c, v.pitch().getId(), at(DAY, h, 0));
		}
		assertThatThrownBy(() -> customer.hold(c, v.pitch().getId(), at(DAY, 13, 0)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("en fazla 3");
	}

}
