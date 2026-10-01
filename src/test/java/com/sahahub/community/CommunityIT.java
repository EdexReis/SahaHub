package com.sahahub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.community.app.ListingQueries;
import com.sahahub.community.app.ListingService;
import com.sahahub.community.app.TeamService;
import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.ListingApplication;
import com.sahahub.community.domain.ListingApplicationRepository;
import com.sahahub.community.domain.ListingRepository;
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Takımlar ve oyuncu/rakip ilanları. */
@IntegrationTest
class CommunityIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	TeamService teams;

	@Autowired
	TeamRepository teamRepo;

	@Autowired
	ListingService listings;

	@Autowired
	ListingQueries queries;

	@Autowired
	ListingRepository listingRepo;

	@Autowired
	ListingApplicationRepository applicationRepo;

	@Autowired
	CustomerBookingService customer;

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

	String code(Long teamId) {
		return teamRepo.findById(teamId).orElseThrow().getInviteCode();
	}

	ListingService.CreateCommand players(Long teamId, int needed) {
		return new ListingService.CreateCommand(Listing.Kind.PLAYERS_WANTED, teamId, null, "İstanbul", "Kadıköy",
				DAY.atTime(21, 0), needed, Listing.Level.CASUAL, null);
	}

	ListingService.CreateCommand opponent(Long teamId) {
		return new ListingService.CreateCommand(Listing.Kind.OPPONENT_WANTED, teamId, null, "İstanbul", null, null,
				null, Listing.Level.INTERMEDIATE, "Rakip arıyoruz");
	}

	int notifications(AppUserPrincipal u, String kind) {
		return jdbc.queryForObject("select count(*) from notification where user_id = ? and kind = ?", Integer.class,
				u.id(), kind);
	}

	// ---------------------------------------------------------------- takımlar

	@Test
	void teamLifecycle() {
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal p1 = data.customer();
		AppUserPrincipal outsider = data.customer();
		Long t = teams.create(cap, "Kartallar", "İstanbul");
		String code = code(t);

		assertThat(teams.preview(p1, code).alreadyMember()).isFalse();
		teams.join(p1, code.toLowerCase(java.util.Locale.ROOT)); // küçük harfle de çalışır
		assertThatThrownBy(() -> teams.join(p1, code)).isInstanceOf(BusinessRuleException.class);
		assertThat(teams.detail(p1, t).members()).hasSize(2);
		assertThat(teams.detail(p1, t).inviteCode()).as("davet kodu yalnızca kaptana").isNull();
		assertThatThrownBy(() -> teams.detail(outsider, t)).isInstanceOf(NotFoundException.class);

		assertThatThrownBy(() -> teams.leave(cap, t)).isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> teams.removeMember(p1, t, cap.id())).isInstanceOf(BusinessRuleException.class);
		teams.transferCaptaincy(cap, t, p1.id());
		assertThat(teams.detail(p1, t).captain()).isTrue();
		assertThat(teams.leave(cap, t)).isFalse();

		teams.regenerateInvite(p1, t);
		assertThatThrownBy(() -> teams.join(outsider, code)).isInstanceOf(NotFoundException.class);
		teams.join(outsider, code(t));
		teams.removeMember(p1, t, outsider.id());
		assertThat(teams.myTeams(outsider)).isEmpty();

		// Tek kalan kaptan ayrılırsa takım dağılır
		assertThat(teams.leave(p1, t)).isTrue();
		assertThat(teamRepo.findById(t).orElseThrow().isActive()).isFalse();
	}

	@Test
	void memberLimitHoldsUnderConcurrentJoins() throws Exception {
		AppUserPrincipal cap = data.customer();
		Long t = teams.create(cap, "Kalabalık", "İstanbul");
		String code = code(t);
		for (int i = 0; i < Team.MAX_MEMBERS - 3; i++) {
			teams.join(data.customer(), code);
		}
		// Kaptan + 22 oyuncu = 23 üye; 3 kişi aynı anda katılmaya çalışır, yalnızca 2 yer var
		List<AppUserPrincipal> late = List.of(data.customer(), data.customer(), data.customer());
		ExecutorService pool = Executors.newFixedThreadPool(3);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Long>> results = new ArrayList<>();
		for (AppUserPrincipal u : late) {
			Callable<Long> task = () -> {
				go.await();
				return teams.join(u, code);
			};
			results.add(pool.submit(task));
		}
		go.countDown();
		int ok = 0;
		for (Future<Long> f : results) {
			try {
				f.get();
				ok++;
			}
			catch (ExecutionException ex) {
				assertThat(ex.getCause()).isInstanceOf(BusinessRuleException.class).hasMessageContaining("dolu");
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from team_member where team_id = ? and left_at is null",
				Integer.class, t)).isEqualTo(Team.MAX_MEMBERS);
	}

	// ---------------------------------------------------------------- ilanlar

	@Test
	void onlyCaptainCanPost_andReservationMustBeOwnConfirmedFuture() {
		Venue v = data.venue();
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal member = data.customer();
		Long t = teams.create(cap, "Kartallar", "İstanbul");
		teams.join(member, code(t));
		assertThatThrownBy(() -> listings.create(member, players(t, 2))).isInstanceOf(BusinessRuleException.class);

		String held = customer.hold(cap, v.pitch().getId(), DAY.atTime(20, 0).atZone(TestData.IST).toInstant());
		Long resId = jdbc.queryForObject("select id from reservation where code = ?", Long.class, held);
		var withRes = new ListingService.CreateCommand(Listing.Kind.OPPONENT_WANTED, t, resId, null, null, null, null,
				Listing.Level.CASUAL, null);
		assertThatThrownBy(() -> listings.create(cap, withRes)).as("henüz onaylanmamış")
			.isInstanceOf(BusinessRuleException.class);
		customer.confirm(cap, held);
		Long id = listings.create(cap, withRes);
		Listing l = listingRepo.findById(id).orElseThrow();
		assertThat(l.getPlayAt()).isEqualTo(DAY.atTime(20, 0).atZone(TestData.IST).toInstant());
		assertThat(l.getExpiresAt()).isEqualTo(l.getPlayAt());
		assertThat(l.getCity()).isEqualTo("İl"); // test şubesinin şehri
		assertThat(queries.detail(null, id).card().place()).contains(v.pitch().getName());

		// Başkasının rezervasyonu kullanılamaz
		AppUserPrincipal other = data.customer();
		Long t2 = teams.create(other, "Diğer", "İstanbul");
		var stolen = new ListingService.CreateCommand(Listing.Kind.OPPONENT_WANTED, t2, resId, null, null, null, null,
				Listing.Level.CASUAL, null);
		assertThatThrownBy(() -> listings.create(other, stolen)).isInstanceOf(NotFoundException.class);

		// Rezervasyon iptal edilince ilan kapanır
		customer.cancel(cap, held);
		assertThat(listingRepo.findById(id).orElseThrow().getStatus()).isEqualTo(Listing.Status.CLOSED);
	}

	@Test
	void openListingLimitPerAuthor() {
		AppUserPrincipal cap = data.customer();
		Long t = teams.create(cap, "Çok İlan", "İstanbul");
		for (int i = 0; i < ListingService.MAX_OPEN_PER_AUTHOR; i++) {
			listings.create(cap, opponent(t));
		}
		assertThatThrownBy(() -> listings.create(cap, opponent(t))).isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void playersListingFillsAndRejectsTheRest_phoneVisibleOnlyAfterAcceptance() {
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal member = data.customer();
		Long t = teams.create(cap, "Şimşekler", "İstanbul");
		teams.join(member, code(t));
		Long id = listings.create(cap, players(t, 2));

		assertThatThrownBy(() -> listings.apply(cap, id, null, null)).isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> listings.apply(member, id, null, null)).isInstanceOf(BusinessRuleException.class);

		AppUserPrincipal a1 = data.customer();
		AppUserPrincipal a2 = data.customer();
		AppUserPrincipal a3 = data.customer();
		Long app1 = listings.apply(a1, id, "Kaleciyim", null);
		Long app2 = listings.apply(a2, id, null, null);
		Long app3 = listings.apply(a3, id, null, null);
		assertThatThrownBy(() -> listings.apply(a1, id, null, null)).isInstanceOf(BusinessRuleException.class);
		assertThat(notifications(cap, "LISTING_APPLICATION")).isEqualTo(3);

		ListingQueries.Detail forAuthor = queries.detail(cap, id);
		assertThat(forAuthor.applications()).hasSize(3).allMatch(a -> a.phone() == null);
		assertThat(queries.detail(a1, id).applications()).as("başvuranlar diğer başvuruları görmez").isEmpty();

		listings.accept(cap, app1);
		assertThat(queries.detail(a1, id).authorPhone()).isNotNull();
		assertThat(queries.detail(cap, id).applications().getFirst().phone()).isNotNull();
		assertThat(queries.detail(a2, id).authorPhone()).isNull();

		listings.accept(cap, app2);
		assertThat(listingRepo.findById(id).orElseThrow().getStatus()).isEqualTo(Listing.Status.FILLED);
		assertThat(applicationRepo.findById(app3).orElseThrow().getStatus()).isEqualTo(ListingApplication.Status.REJECTED);
		assertThat(notifications(a3, "LISTING_REJECTED")).isEqualTo(1);
		assertThat(notifications(a1, "LISTING_ACCEPTED")).isEqualTo(1);
		assertThat(queries.open(null, null)).noneMatch(c -> c.id().equals(id));
	}

	/** Rakip ilanında iki başvuru aynı anda kabul edilmeye çalışılırsa yalnızca biri kabul edilir. */
	@Test
	void opponentListingAcceptsOnlyOneUnderConcurrency() throws Exception {
		AppUserPrincipal cap = data.customer();
		Long t = teams.create(cap, "Ev Sahibi", "İstanbul");
		Long id = listings.create(cap, opponent(t));
		List<Long> apps = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			AppUserPrincipal rivalCap = data.customer();
			Long rival = teams.create(rivalCap, "Rakip " + i, "İstanbul");
			assertThatThrownBy(() -> listings.apply(rivalCap, id, null, null)).as("takım seçmeden rakip olunmaz")
				.isInstanceOf(BusinessRuleException.class);
			apps.add(listings.apply(rivalCap, id, null, rival));
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();
		for (Long a : apps) {
			results.add(pool.submit(() -> {
				go.await();
				listings.accept(cap, a);
				return null;
			}));
		}
		go.countDown();
		int ok = 0;
		for (Future<?> f : results) {
			try {
				f.get();
				ok++;
			}
			catch (ExecutionException ex) {
				assertThat(ex.getCause()).isInstanceOf(BusinessRuleException.class);
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from listing_application where listing_id = ? and status = 'ACCEPTED'",
				Integer.class, id)).isEqualTo(1);
		assertThat(listingRepo.findById(id).orElseThrow().getStatus()).isEqualTo(Listing.Status.FILLED);
	}

	@Test
	void expiredListingStopsTakingApplications_andDisbandClosesListings() {
		AppUserPrincipal cap = data.customer();
		Long t = teams.create(cap, "Süreli", "İstanbul");
		Long id = listings.create(cap, opponent(t)); // zaman yok → 7 gün açık
		assertThat(queries.open(null, null)).anyMatch(c -> c.id().equals(id));

		clock.advance(ListingService.DEFAULT_LIFETIME.plus(Duration.ofMinutes(1)));
		AppUserPrincipal rivalCap = data.customer();
		Long rival = teams.create(rivalCap, "Geç Rakip", "İstanbul");
		assertThatThrownBy(() -> listings.apply(rivalCap, id, null, rival)).isInstanceOf(BusinessRuleException.class);
		assertThat(queries.open(null, null)).noneMatch(c -> c.id().equals(id));
		assertThat(queries.detail(null, id).card().statusLabel()).isEqualTo("Süresi doldu");

		Long fresh = listings.create(cap, opponent(t));
		teams.disband(cap, t);
		assertThat(listingRepo.findById(fresh).orElseThrow().getStatus()).isEqualTo(Listing.Status.CLOSED);
		assertThat(Instant.now(clock)).isAfter(IntegrationTest.START);
	}

}
