package com.sahahub.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.app.WaitlistService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.booking.domain.WaitlistEntry;
import com.sahahub.booking.domain.WaitlistRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;
import com.sahahub.tournament.app.TournamentQueries;
import com.sahahub.tournament.app.TournamentService;
import com.sahahub.tournament.domain.TournamentMatch;
import com.sahahub.tournament.domain.TournamentMatchRepository;

/** Lig: fikstür, maç planlama (senaryo 14: maç ile rezervasyon çakışmaz), skor ve puan durumu, yetki. */
@IntegrationTest
class TournamentIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3); // Salı

	@Autowired
	TournamentService service;

	@Autowired
	TournamentQueries queries;

	@Autowired
	TournamentMatchRepository matches;

	@Autowired
	StaffReservationService staff;

	@Autowired
	CustomerBookingService customer;

	@Autowired
	WaitlistService waitlist;

	@Autowired
	WaitlistRepository waitlistEntries;

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

	static Instant at(LocalDate d, int hour, int minute) {
		return d.atTime(hour, minute).atZone(TestData.IST).toInstant();
	}

	/** n takımlı, fikstürü oluşturulmuş lig. */
	Long league(Venue v, int n) {
		Long id = service.create(v.manager(), v.branch().getId(), "Test Ligi", false, 3, 1, 0);
		for (int i = 1; i <= n; i++) {
			service.addEntry(v.manager(), id, "Takım " + i);
		}
		service.start(v.manager(), id);
		return id;
	}

	List<TournamentMatch> matchesOf(Long id) {
		return matches.findByTournamentIdOrderByRoundAscIdAsc(id);
	}

	void book(Venue v, LocalDate d, int hour, int minute) {
		staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(v.pitch().getId(),
				d.atTime(hour, minute), 60, Channel.PHONE, null, "Müşteri", null, null));
	}

	@Test
	void scenario14_matchAndReservationNeverOverlap() {
		Venue v = data.venue();
		Long id = league(v, 3);
		Long m = matchesOf(id).getFirst().getId();
		book(v, DAY, 20, 0);

		// Rezervasyonla kesişen saate maç konamaz
		assertThatThrownBy(() -> service.schedule(v.manager(), m, v.pitch().getId(), DAY.atTime(20, 30), 60))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("dolu");
		assertThat(matches.findById(m).orElseThrow().getStatus()).isEqualTo(TournamentMatch.Status.UNSCHEDULED);

		// Maç olan saate rezervasyon yapılamaz
		service.schedule(v.manager(), m, v.pitch().getId(), DAY.atTime(21, 0), 60);
		assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0)))
			.isInstanceOf(SlotUnavailableException.class);

		// Dolu saate yeniden planlama reddedilir; maç eski saatinde, doluluk yerinde kalır
		assertThatThrownBy(() -> service.schedule(v.manager(), m, v.pitch().getId(), DAY.atTime(20, 0), 60))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("eski saatinde");
		assertThat(matches.findById(m).orElseThrow().getStartsAt()).isEqualTo(at(DAY, 21, 0));
		assertThatThrownBy(() -> customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0)))
			.isInstanceOf(SlotUnavailableException.class);

		// Başka saate taşınınca eski saat boşalır
		service.schedule(v.manager(), m, v.pitch().getId(), DAY.atTime(22, 0), 60);
		assertThat(customer.hold(data.customer(), v.pitch().getId(), at(DAY, 21, 0))).isNotBlank();
		assertThat(jdbc.queryForObject("""
				select count(*) from pitch_occupancy where source_type = 'TOURNAMENT_MATCH' and source_id = ? and active""",
				Integer.class, m)).isEqualTo(1);
	}

	@Test
	void weeklyPlanningIsAllOrNothing() {
		Venue v = data.venue();
		Long id = league(v, 4); // 6 maç, 3 hafta, haftada 2 maç
		book(v, DAY.plusWeeks(1), 21, 0); // 2. haftanın ikinci maçının saati

		assertThatThrownBy(() -> service.planWeekly(v.manager(), id, DAY, LocalTime.of(20, 0), 60, v.pitch().getId()))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("2. hafta")
			.hasMessageContaining("Hiçbir maç planlanmadı");
		assertThat(matchesOf(id)).allMatch(m -> m.getStatus() == TournamentMatch.Status.UNSCHEDULED);
		assertThat(jdbc.queryForObject("select count(*) from pitch_occupancy where source_type = 'TOURNAMENT_MATCH'"
				+ " and pitch_id = ? and active", Integer.class, v.pitch().getId())).isZero();

		assertThat(service.planWeekly(v.manager(), id, DAY, LocalTime.of(22, 0), 60, v.pitch().getId())).isEqualTo(6);
		List<TournamentMatch> all = matchesOf(id);
		assertThat(all).extracting(TournamentMatch::getStartsAt)
			.containsExactly(at(DAY, 22, 0), at(DAY, 23, 0), at(DAY.plusWeeks(1), 22, 0), at(DAY.plusWeeks(1), 23, 0),
					at(DAY.plusWeeks(2), 22, 0), at(DAY.plusWeeks(2), 23, 0));
		// Şube 01:00'de kapanır: 00:30'da başlayan 60 dakikalık maç kapanışı aşar
		Venue v2 = data.venue();
		Long other = league(v2, 3);
		assertThatThrownBy(
				() -> service.planWeekly(v2.manager(), other, DAY, LocalTime.of(0, 30), 60, v2.pitch().getId()))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("kapalı");
	}

	@Test
	void resultsStandingsAndFinish() {
		Venue v = data.venue();
		Long id = league(v, 3);
		service.planWeekly(v.manager(), id, DAY, LocalTime.of(20, 0), 60, v.pitch().getId());
		List<TournamentMatch> all = matchesOf(id);
		TournamentMatch first = all.getFirst();

		assertThatThrownBy(() -> service.recordResult(v.manager(), first.getId(), 2, 1))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("başlamadan");
		assertThatThrownBy(() -> service.finish(v.manager(), id)).isInstanceOf(BusinessRuleException.class);

		clock.set(at(DAY.plusWeeks(3), 0, 0));
		for (TournamentMatch m : all) {
			service.recordResult(v.manager(), m.getId(), 1, 0); // ev sahibi kazanır
		}
		service.recordResult(v.manager(), first.getId(), 0, 0); // düzeltme
		TournamentQueries.Detail d = queries.forStaff(v.manager(), id);
		assertThat(d.unplayed()).isZero();
		assertThat(d.standings()).extracting(r -> r.played()).containsOnly(2);
		assertThat(d.standings().stream().mapToInt(r -> r.points()).sum()).isEqualTo(3 + 3 + 2);
		assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'MATCH_RESULT_CORRECTED'"
				+ " and entity_id = ?", Integer.class, first.getId())).isEqualTo(1);

		assertThatThrownBy(() -> service.schedule(v.manager(), first.getId(), v.pitch().getId(), DAY.plusWeeks(5).atTime(20, 0), 60))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("Oynanmış maçın saati");
		service.finish(v.manager(), id);
		assertThatThrownBy(() -> service.recordResult(v.manager(), first.getId(), 5, 0))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(queries.forPublic(id).standings()).hasSize(3);
	}

	@Test
	void draftRules() {
		Venue v = data.venue();
		Long id = service.create(v.manager(), v.branch().getId(), "Taslak", true, 3, 1, 0);
		service.addEntry(v.manager(), id, "Kartallar");
		assertThatThrownBy(() -> service.addEntry(v.manager(), id, "kartallar")).isInstanceOf(BusinessRuleException.class);
		service.addEntry(v.manager(), id, "Şimşekler");
		assertThatThrownBy(() -> service.start(v.manager(), id)).isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> queries.forPublic(id)).isInstanceOf(NotFoundException.class);
		service.addEntry(v.manager(), id, "Yıldızlar");
		assertThat(service.start(v.manager(), id)).isEqualTo(6); // çift devre: 3 takım → 6 maç
		assertThatThrownBy(() -> service.start(v.manager(), id)).isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> service.addEntry(v.manager(), id, "Geç Kalan")).isInstanceOf(BusinessRuleException.class);
		assertThatThrownBy(() -> service.create(v.manager(), v.branch().getId(), "Ters puan", false, 1, 3, 0))
			.isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void onlyManagersOfThisBranchCanManage() {
		Venue v = data.venue();
		Venue other = data.venue();
		Long id = league(v, 3);
		Long m = matchesOf(id).getFirst().getId();
		assertThatThrownBy(() -> service.create(v.reception(), v.branch().getId(), "X", false, 3, 1, 0))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.schedule(v.reception(), m, v.pitch().getId(), DAY.atTime(20, 0), 60))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> service.schedule(other.owner(), m, v.pitch().getId(), DAY.atTime(20, 0), 60))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> queries.forStaff(other.owner(), id)).isInstanceOf(AccessDeniedException.class);
		// Başka şubenin sahası seçilemez
		assertThatThrownBy(() -> service.schedule(v.manager(), m, other.pitch().getId(), DAY.atTime(20, 0), 60))
			.isInstanceOf(NotFoundException.class);
	}

	/** Maçın planı kaldırılınca o saat için sıradaki müşteriye teklif açılır. */
	@Test
	void unschedulingAMatchOffersTheSlotToTheWaitlist() {
		Venue v = data.venue();
		Long id = league(v, 3);
		Long m = matchesOf(id).getFirst().getId();
		service.schedule(v.manager(), m, v.pitch().getId(), DAY.atTime(20, 0), 60);
		AppUserPrincipal c = data.customer();
		Long entry = waitlist.join(c, v.pitch().getId(), at(DAY, 20, 0));

		clock.advance(Duration.ofMinutes(1));
		service.unschedule(v.manager(), m);

		WaitlistEntry e = waitlistEntries.findById(entry).orElseThrow();
		assertThat(e.getStatus()).isEqualTo(WaitlistEntry.Status.OFFERED);
	}

}
