package com.sahahub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.HoldExpiryService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.app.WaitlistService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.booking.domain.WaitlistEntry;
import com.sahahub.booking.domain.WaitlistRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.TimeRange;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Senaryo 15: bekleme listesi — boşalan saat sıradakine teklif edilir, süre dolunca sonrakine geçer. */
@IntegrationTest
class WaitlistIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3); // Salı

	@Autowired
	WaitlistService waitlist;

	@Autowired
	WaitlistRepository entries;

	@Autowired
	CustomerBookingService customer;

	@Autowired
	StaffReservationService staff;

	@Autowired
	HoldExpiryService expiry;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	static Instant at(int hour) {
		return DAY.atTime(hour, 0).atZone(TestData.IST).toInstant();
	}

	String bookTwenty(Venue v) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Dolu Takım", null, null));
	}

	WaitlistEntry entry(Long id) {
		return entries.findById(id).orElseThrow();
	}

	@Test
	void joinRules() {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		assertThatThrownBy(() -> waitlist.join(c, v.pitch().getId(), at(20)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("boş");
		bookTwenty(v);
		waitlist.join(c, v.pitch().getId(), at(20));
		assertThatThrownBy(() -> waitlist.join(c, v.pitch().getId(), at(20)))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("zaten");
	}

	@Test
	void cancellationOffersSlotToFirstInLine_othersCannotTakeIt_confirmAccepts() {
		Venue v = data.venue();
		String taken = bookTwenty(v);
		AppUserPrincipal first = data.customer();
		AppUserPrincipal second = data.customer();
		Long e1 = waitlist.join(first, v.pitch().getId(), at(20));
		clock.advance(Duration.ofMinutes(1));
		Long e2 = waitlist.join(second, v.pitch().getId(), at(20));
		assertThat(waitlist.mine(second).getFirst().position()).isEqualTo(2);

		staff.cancel(v.reception(), taken, "Takım gelemiyor");

		WaitlistEntry offered = entry(e1);
		assertThat(offered.getStatus()).isEqualTo(WaitlistEntry.Status.OFFERED);
		assertThat(entry(e2).getStatus()).isEqualTo(WaitlistEntry.Status.WAITING);
		Reservation hold = reservations.findById(offered.getOfferReservationId()).orElseThrow();
		assertThat(hold.getStatus()).isEqualTo(ReservationStatus.HELD);
		assertThat(hold.getCustomerId()).isEqualTo(first.id());
		assertThat(hold.getHoldExpiresAt()).isEqualTo(clock.instant().plus(waitlist.offerDuration()));

		// Sırada olmayan biri (ya da ikinci sıradaki) saati doğrudan alamaz
		assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(20)))
			.isInstanceOf(SlotUnavailableException.class);
		assertThatThrownBy(() -> customer.hold(second, v.pitch().getId(), at(20)))
			.isInstanceOf(SlotUnavailableException.class);

		customer.confirm(first, hold.getCode());
		assertThat(entry(e1).getStatus()).isEqualTo(WaitlistEntry.Status.ACCEPTED);
		assertThat(entry(e2).getStatus()).isEqualTo(WaitlistEntry.Status.WAITING);
		assertThat(jdbc.queryForObject(
				"select count(*) from notification where user_id = ? and kind = 'WAITLIST_OFFER'", Integer.class,
				first.id())).isEqualTo(1);
	}

	@Test
	void expiredOfferMovesToNextInLine() {
		Venue v = data.venue();
		String taken = bookTwenty(v);
		AppUserPrincipal first = data.customer();
		AppUserPrincipal second = data.customer();
		Long e1 = waitlist.join(first, v.pitch().getId(), at(20));
		clock.advance(Duration.ofMinutes(1));
		Long e2 = waitlist.join(second, v.pitch().getId(), at(20));
		staff.cancel(v.reception(), taken, "İptal");
		Long firstHold = entry(e1).getOfferReservationId();

		clock.advance(waitlist.offerDuration().plusSeconds(1));
		expiry.expireDueHolds();

		assertThat(entry(e1).getStatus()).isEqualTo(WaitlistEntry.Status.EXPIRED);
		assertThat(reservations.findById(firstHold).orElseThrow().getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		WaitlistEntry next = entry(e2);
		assertThat(next.getStatus()).isEqualTo(WaitlistEntry.Status.OFFERED);
		assertThat(reservations.findById(next.getOfferReservationId()).orElseThrow().getCustomerId())
			.isEqualTo(second.id());
	}

	@Test
	void offerNotConfirmedAndQueueEmpty_slotBecomesFreeForEveryone() {
		Venue v = data.venue();
		String taken = bookTwenty(v);
		AppUserPrincipal first = data.customer();
		waitlist.join(first, v.pitch().getId(), at(20));
		staff.cancel(v.reception(), taken, "İptal");
		clock.advance(waitlist.offerDuration().plusSeconds(1));
		expiry.expireDueHolds();
		assertThat(customer.hold(data.customer(), v.pitch().getId(), at(20))).isNotBlank();
	}

	@Test
	void leaveRemovesFromQueue() {
		Venue v = data.venue();
		bookTwenty(v);
		AppUserPrincipal c = data.customer();
		Long id = waitlist.join(c, v.pitch().getId(), at(20));
		assertThatThrownBy(() -> waitlist.leave(data.customer(), id)).isInstanceOf(RuntimeException.class);
		waitlist.leave(c, id);
		assertThat(entry(id).getStatus()).isEqualTo(WaitlistEntry.Status.LEFT);
		assertThat(waitlist.mine(c)).isEmpty();
		// Çıktıktan sonra yeniden sıraya girebilir (en sona)
		assertThat(waitlist.join(c, v.pitch().getId(), at(20))).isNotEqualTo(id);
	}

	/**
	 * Saat boşken birden fazla süreç aynı anda "sıradakine teklif aç" derse yalnızca bir teklif
	 * oluşur (EXCLUDE kısıtı + ux_waitlist_one_offer).
	 */
	@Test
	void concurrentOfferAttemptsCreateExactlyOneOffer() throws Exception {
		Venue v = data.venue();
		List<AppUserPrincipal> people = List.of(data.customer(), data.customer(), data.customer());
		Instant now = clock.instant();
		tx.executeWithoutResult(s -> {
			for (int i = 0; i < people.size(); i++) {
				entries.save(new WaitlistEntry(v.business().getId(), v.branch().getId(), v.pitch().getId(),
						people.get(i).id(), new TimeRange(at(20), at(21)), now.plusSeconds(i)));
			}
		});

		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Callable<Integer> task = () -> {
				go.await();
				return waitlist.offerNext(v.pitch().getId(), at(20), at(21));
			};
			results.add(pool.submit(task));
		}
		go.countDown();
		int offers = 0;
		for (Future<Integer> f : results) {
			offers += f.get();
		}
		pool.shutdown();

		assertThat(offers).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from waitlist_entry where pitch_id = ? and status = 'OFFERED'",
				Integer.class, v.pitch().getId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from reservation where pitch_id = ? and status = 'HELD'",
				Integer.class, v.pitch().getId())).isEqualTo(1);
		// Teklif sıradaki ilk kişiye gitti
		assertThat(jdbc.queryForObject(
				"select customer_id from waitlist_entry where pitch_id = ? and status = 'OFFERED'", Long.class,
				v.pitch().getId())).isEqualTo(people.getFirst().id());
	}

}
