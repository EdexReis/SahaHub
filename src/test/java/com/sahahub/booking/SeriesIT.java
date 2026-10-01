package com.sahahub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahahub.booking.app.SeriesService;
import com.sahahub.booking.app.SeriesService.Mode;
import com.sahahub.booking.app.SeriesService.OccurrenceStatus;
import com.sahahub.booking.app.SeriesService.SeriesCommand;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Senaryo 13: düzenli (haftalık) rezervasyon — önizleme, çakışma, tümü-ya-hiçbiri, seri iptali. */
@IntegrationTest
class SeriesIT {

	static final LocalDate FIRST = LocalDate.of(2026, 3, 3); // Salı

	@Autowired
	SeriesService series;

	@Autowired
	StaffReservationService staff;

	@Autowired
	ReservationRepository reservations;

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

	static SeriesCommand weekly(Venue v, int occurrences) {
		return new SeriesCommand(v.pitch().getId(), FIRST, LocalTime.of(20, 0), 60, occurrences, Channel.PHONE, null,
				"Salı Takımı", "0555 111 22 33", null);
	}

	int confirmedOn(Venue v) {
		return jdbc.queryForObject("select count(*) from reservation where pitch_id = ? and status = 'CONFIRMED'",
				Integer.class, v.pitch().getId());
	}

	int seriesRows(Venue v) {
		return jdbc.queryForObject("select count(*) from reservation_series where pitch_id = ?", Integer.class,
				v.pitch().getId());
	}

	@Test
	void previewMarksConflict_allIsRefused_selectedCreatesOnlyChosenDates() {
		Venue v = data.venue();
		staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(v.pitch().getId(),
				FIRST.plusWeeks(2).atTime(20, 30), 60, Channel.PHONE, null, "Başka Takım", null, null));

		SeriesService.Preview p = series.preview(v.reception(), v.branch().getId(), weekly(v, 4));
		assertThat(p.items()).extracting(SeriesService.Occurrence::status)
			.containsExactly(OccurrenceStatus.AVAILABLE, OccurrenceStatus.AVAILABLE, OccurrenceStatus.CONFLICT,
					OccurrenceStatus.AVAILABLE);
		assertThat(p.allAvailable()).isFalse();

		assertThatThrownBy(() -> series.create(v.reception(), v.branch().getId(), weekly(v, 4), Mode.ALL, null))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("2026-03-17");
		assertThat(confirmedOn(v)).isEqualTo(1);
		assertThat(seriesRows(v)).isZero();

		// Dolu tarih seçilirse yine reddedilir
		assertThatThrownBy(() -> series.create(v.reception(), v.branch().getId(), weekly(v, 4), Mode.SELECTED,
				List.of(FIRST, FIRST.plusWeeks(2))))
			.isInstanceOf(BusinessRuleException.class);

		Long id = series.create(v.reception(), v.branch().getId(), weekly(v, 4), Mode.SELECTED,
				List.of(FIRST, FIRST.plusWeeks(1), FIRST.plusWeeks(3)));
		List<Reservation> created = reservations.findBySeriesIdOrderBySeriesIndex(id);
		assertThat(created).extracting(Reservation::getSeriesIndex).containsExactly(1, 2, 4);
		assertThat(created).allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED);
		assertThat(created.getFirst().getGuestName()).isEqualTo("Salı Takımı");
		assertThat(confirmedOn(v)).isEqualTo(4);
	}

	@Test
	void occurrenceLimitsAreEnforced() {
		Venue v = data.venue();
		int max = series.maxOccurrences();
		assertThatThrownBy(() -> series.preview(v.reception(), v.branch().getId(), weekly(v, max + 1)))
			.isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> series.preview(v.reception(), v.branch().getId(), weekly(v, 1)))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(series.preview(v.reception(), v.branch().getId(), weekly(v, max)).items()).hasSize(max);
	}

	/**
	 * İki personel aynı seriyi aynı anda oluşturur. Biri tamamen başarılı olur; diğeri hiçbir maç
	 * oluşturmaz (yarım seri kalmaz).
	 */
	@Test
	void concurrentSeriesNeverLeavesPartialSeries() throws Exception {
		Venue v = data.venue();
		int n = 6;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Long>> results = new ArrayList<>();
		for (var who : List.of(v.reception(), v.manager())) {
			Callable<Long> task = () -> {
				start.await();
				return series.create(who, v.branch().getId(), weekly(v, n), Mode.ALL, null);
			};
			results.add(pool.submit(task));
		}
		start.countDown();
		int ok = 0;
		int refused = 0;
		for (Future<Long> f : results) {
			try {
				f.get();
				ok++;
			}
			catch (java.util.concurrent.ExecutionException ex) {
				assertThat(ex.getCause()).isInstanceOf(BusinessRuleException.class);
				refused++;
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(refused).isEqualTo(1);
		assertThat(seriesRows(v)).isEqualTo(1);
		assertThat(confirmedOn(v)).isEqualTo(n);
	}

	@Test
	void cancelFromHereCancelsThisAndLaterOnly_andFreesTheSlots() {
		Venue v = data.venue();
		Long id = series.create(v.reception(), v.branch().getId(), weekly(v, 4), Mode.ALL, null);
		List<Reservation> list = reservations.findBySeriesIdOrderBySeriesIndex(id);

		SeriesService.SeriesInfo info = series.info(v.reception(), list.get(1).getCode());
		assertThat(info.index()).isEqualTo(2);
		assertThat(info.total()).isEqualTo(4);
		assertThat(info.openFromHere()).isEqualTo(3);

		assertThatThrownBy(() -> series.cancelFrom(v.reception(), list.get(1).getCode(), " "))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(series.cancelFrom(v.reception(), list.get(1).getCode(), "Takım dağıldı")).isEqualTo(3);

		List<Reservation> after = reservations.findBySeriesIdOrderBySeriesIndex(id);
		assertThat(after).extracting(Reservation::getStatus)
			.containsExactly(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED, ReservationStatus.CANCELLED,
					ReservationStatus.CANCELLED);
		// Boşalan saat yeniden satılabilir
		assertThat(staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), FIRST.plusWeeks(2).atTime(20, 0), 60, Channel.PHONE, null, "Yeni", null, null)))
			.isNotBlank();
	}

	@Test
	void anotherBusinessCannotCreateSeriesOnMyBranch() {
		Venue mine = data.venue();
		Venue other = data.venue();
		assertThatThrownBy(() -> series.preview(other.owner(), mine.branch().getId(), weekly(mine, 3)))
			.isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
	}

}
