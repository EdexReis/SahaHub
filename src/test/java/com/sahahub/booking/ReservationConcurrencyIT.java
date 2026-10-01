package com.sahahub.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/**
 * Senaryo 1: Aynı saate eşzamanlı iki rezervasyon isteği → yalnızca biri başarılı olmalı.
 * Gerçek PostgreSQL üzerinde, gerçekten paralel iş parçacıklarıyla denenir.
 */
@IntegrationTest
class ReservationConcurrencyIT {

	@Autowired
	CustomerBookingService customerBooking;

	@Autowired
	StaffReservationService staffBooking;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	@RepeatedTest(5)
	void twoCustomersSameSlotAtTheSameTime_onlyOneWins() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal alice = data.customer();
		AppUserPrincipal bob = data.customer();
		Instant start = DAY.atTime(21, 0).atZone(TestData.IST).toInstant();

		List<Outcome> outcomes = runConcurrently(
				() -> customerBooking.hold(alice, v.pitch().getId(), start),
				() -> customerBooking.hold(bob, v.pitch().getId(), start));

		assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.error() instanceof SlotUnavailableException).hasSize(1);
		assertThat(activeOccupancies(v.pitch().getId())).isEqualTo(1);
	}

	@Test
	void customerAndStaffRaceForSameSlot_onlyOneWins() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal customer = data.customer();
		Instant start = DAY.atTime(20, 0).atZone(TestData.IST).toInstant();
		var staffCmd = new StaffReservationService.CreateCommand(v.pitch().getId(), DAY.atTime(20, 30), 60,
				Channel.PHONE, null, "Telefon Müşterisi", null, null);

		List<Outcome> outcomes = runConcurrently(
				() -> customerBooking.hold(customer, v.pitch().getId(), start),
				() -> staffBooking.create(v.reception(), v.branch().getId(), staffCmd));

		assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.error() instanceof SlotUnavailableException).hasSize(1);
	}

	@Test
	void tenParallelRequestsForOneSlot_exactlyOneSucceeds() throws Exception {
		Venue v = data.venue();
		Instant start = DAY.atTime(22, 0).atZone(TestData.IST).toInstant();
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			AppUserPrincipal c = data.customer();
			calls.add(() -> customerBooking.hold(c, v.pitch().getId(), start));
		}
		List<Outcome> outcomes = runConcurrently(calls.toArray(Callable[]::new));
		assertThat(outcomes).filteredOn(Outcome::success).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o.error() instanceof SlotUnavailableException).hasSize(9);
	}

	private int activeOccupancies(Long pitchId) {
		return jdbc.queryForObject("select count(*) from pitch_occupancy where pitch_id = ? and active", Integer.class,
				pitchId);
	}

	record Outcome(boolean success, Throwable error) {
	}

	/** Tüm görevleri aynı anda başlatır (kapı mandalı) ve sonuçlarını toplar. */
	@SafeVarargs
	private static List<Outcome> runConcurrently(Callable<String>... tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
		CountDownLatch gate = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (Callable<String> task : tasks) {
				futures.add(pool.submit(() -> {
					gate.await();
					return task.call();
				}));
			}
			gate.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<String> f : futures) {
				try {
					f.get();
					outcomes.add(new Outcome(true, null));
				}
				catch (java.util.concurrent.ExecutionException ex) {
					outcomes.add(new Outcome(false, ex.getCause()));
				}
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
