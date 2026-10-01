package com.sahahub.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;
import com.sahahub.tournament.app.TournamentQueries;
import com.sahahub.tournament.app.TournamentService;
import com.sahahub.tournament.domain.Tournament;
import com.sahahub.tournament.domain.TournamentEntry;
import com.sahahub.tournament.domain.TournamentEntryRepository;
import com.sahahub.tournament.domain.TournamentMatch;
import com.sahahub.tournament.domain.TournamentMatchRepository;

/** Eleme usulü turnuva: bay, tur atlama, penaltı, düzeltme kuralı, eşzamanlı sonuç, saha çakışması. */
@IntegrationTest
@AutoConfigureMockMvc
class KnockoutIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	TournamentService service;

	@Autowired
	TournamentQueries queries;

	@Autowired
	TournamentMatchRepository matches;

	@Autowired
	TournamentEntryRepository entries;

	@Autowired
	StaffReservationService staff;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

	/** Her yeni maçı farklı saate koymak için sayaç (09:00'dan itibaren saat saat). */
	int nextHour;

	@BeforeEach
	void reset() {
		clock.set(IntegrationTest.START);
		nextHour = 0;
	}

	Long cup(Venue v, int n) {
		Long id = service.create(v.manager(), v.branch().getId(), "Kupa", Tournament.Format.KNOCKOUT, false, 3, 1, 0);
		for (int i = 1; i <= n; i++) {
			service.addEntry(v.manager(), id, "T" + i); // T1 = 1. tohum
		}
		return id;
	}

	Long entry(Long cup, String name) {
		return entries.findByTournamentIdOrderById(cup).stream().filter(e -> e.getName().equals(name))
			.map(TournamentEntry::getId).findFirst().orElseThrow();
	}

	List<TournamentMatch> all(Long cup) {
		return matches.findByTournamentIdOrderByRoundAscIdAsc(cup);
	}

	TournamentMatch match(Long cup, int round, int slot) {
		return all(cup).stream().filter(m -> m.getRound() == round && m.getBracketSlot() == slot).findFirst()
			.orElseThrow();
	}

	/** Maçı ilk boş saate planlar ve saati maçtan sonraya alır (skor ancak başladıktan sonra girilir). */
	void play(Venue v, TournamentMatch stale, int home, int away, Long penaltyWinner) {
		TournamentMatch m = matches.findById(stale.getId()).orElseThrow();
		if (m.getStatus() == TournamentMatch.Status.UNSCHEDULED) {
			// Test saatinden sonraki ilk tam saat (şube 09:00–01:00 açık)
			java.time.LocalDateTime next = clock.instant().atZone(TestData.IST).toLocalDateTime()
				.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
			if (next.getHour() < 9 && next.getHour() > 0) {
				next = next.withHour(9);
			}
			service.schedule(v.manager(), m.getId(), v.pitch().getId(), next, 60);
		}
		TournamentMatch fresh = matches.findById(m.getId()).orElseThrow();
		if (clock.instant().isBefore(fresh.getEndsAt())) {
			clock.set(fresh.getEndsAt());
		}
		service.recordResult(v.manager(), m.getId(), home, away, penaltyWinner);
	}

	@Test
	void sixTeams_byesForTopSeeds_roundsOpenAsResultsArrive_championAndFinish() {
		Venue v = data.venue();
		Long cup = cup(v, 6);
		assertThat(service.start(v.manager(), cup)).as("ilk turda yalnızca bay olmayan 2 maç").isEqualTo(2);
		// 8'lik ağaç: 1-bay, 4-5, 2-bay, 3-6
		TournamentMatch m45 = match(cup, 1, 1);
		TournamentMatch m36 = match(cup, 1, 3);
		assertThat(List.of(m45.getHomeEntryId(), m45.getAwayEntryId())).containsExactly(entry(cup, "T4"), entry(cup, "T5"));
		assertThat(List.of(m36.getHomeEntryId(), m36.getAwayEntryId())).containsExactly(entry(cup, "T3"), entry(cup, "T6"));

		assertThatThrownBy(() -> service.finish(v.manager(), cup)).isInstanceOf(BusinessRuleException.class);
		play(v, m45, 2, 1, null);
		// 4-5'in galibi 1. tohumun rakibi olur; yarı final hemen açılır
		TournamentMatch sf1 = match(cup, 2, 0);
		assertThat(List.of(sf1.getHomeEntryId(), sf1.getAwayEntryId())).containsExactly(entry(cup, "T1"), entry(cup, "T4"));

		// Eleme maçı berabere bitemez
		assertThatThrownBy(() -> play(v, m36, 1, 1, null)).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("penaltı");
		play(v, m36, 1, 1, entry(cup, "T6")); // penaltılarla 6. tohum
		TournamentMatch sf2 = match(cup, 2, 1);
		assertThat(List.of(sf2.getHomeEntryId(), sf2.getAwayEntryId())).containsExactly(entry(cup, "T2"), entry(cup, "T6"));
		assertThat(matches.findById(m36.getId()).orElseThrow().isDecidedByPenalties()).isTrue();

		play(v, sf1, 0, 3, null); // T4 finale
		play(v, sf2, 2, 0, null); // T2 finale
		TournamentMatch fin = match(cup, 3, 0);
		assertThat(List.of(fin.getHomeEntryId(), fin.getAwayEntryId())).containsExactly(entry(cup, "T4"), entry(cup, "T2"));
		play(v, fin, 1, 0, null);

		TournamentQueries.Detail d = queries.forStaff(v.manager(), cup);
		assertThat(d.champion()).isEqualTo("T4");
		assertThat(d.matchCount()).isEqualTo(5); // takım − 1
		assertThat(d.bracket()).extracting(TournamentQueries.BracketRound::name)
			.containsExactly("Çeyrek final", "Yarı final", "Final");
		assertThat(d.bracket().getFirst().cells().stream().filter(TournamentQueries.BracketCell::bye)).hasSize(2);
		assertThat(d.canFinish()).isTrue();
		service.finish(v.manager(), cup);
		assertThat(queries.forPublic(cup).champion()).isEqualTo("T4");
	}

	@Test
	void correctionChangesNextRoundOnlyIfNotPlayed() {
		Venue v = data.venue();
		Long cup = cup(v, 4); // 1-4, 2-3; final
		service.start(v.manager(), cup);
		TournamentMatch a = match(cup, 1, 0);
		TournamentMatch b = match(cup, 1, 1);
		play(v, a, 2, 0, null); // T1
		play(v, b, 0, 1, null); // T3
		TournamentMatch fin = match(cup, 2, 0);
		assertThat(fin.getHomeEntryId()).isEqualTo(entry(cup, "T1"));

		// Final oynanmadan düzeltme: galip T4 olur, finaldeki takım güncellenir
		play(v, a, 0, 2, null);
		assertThat(matches.findById(fin.getId()).orElseThrow().getHomeEntryId()).isEqualTo(entry(cup, "T4"));
		assertThat(all(cup)).hasSize(3);

		play(v, fin, 1, 0, null);
		// Final oynandıktan sonra galibi değiştiren düzeltme reddedilir; galibi değiştirmeyen skor düzeltmesi olur
		assertThatThrownBy(() -> service.recordResult(v.manager(), a.getId(), 3, 0, null))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("sonraki tur maçı oynandı");
		service.recordResult(v.manager(), a.getId(), 0, 4, null);
		assertThat(matches.findById(a.getId()).orElseThrow().getAwayScore()).isEqualTo(4);
		assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'MATCH_RESULT_CORRECTED'"
				+ " and entity_id = ?", Integer.class, a.getId())).isEqualTo(2);
	}

	/** İki yarı final sonucu aynı anda girilir: final tek kez açılır. */
	@Test
	void concurrentResultsOpenTheFinalExactlyOnce() throws Exception {
		Venue v = data.venue();
		Long cup = cup(v, 4);
		service.start(v.manager(), cup);
		List<TournamentMatch> semis = all(cup);
		for (TournamentMatch m : semis) {
			service.schedule(v.manager(), m.getId(), v.pitch().getId(), DAY.atTime(9 + nextHour++, 0), 60);
		}
		clock.set(Instant.parse("2026-03-04T00:00:00Z"));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();
		for (TournamentMatch m : semis) {
			Callable<Object> task = () -> {
				go.await();
				service.recordResult(v.manager(), m.getId(), 1, 0, null);
				return null;
			};
			results.add(pool.submit(task));
		}
		go.countDown();
		for (Future<?> f : results) {
			f.get();
		}
		pool.shutdown();
		assertThat(jdbc.queryForObject("select count(*) from tournament_match where tournament_id = ? and round = 2",
				Integer.class, cup)).isEqualTo(1);
		TournamentMatch fin = match(cup, 2, 0);
		assertThat(List.of(fin.getHomeEntryId(), fin.getAwayEntryId())).containsExactly(entry(cup, "T1"), entry(cup, "T2"));
	}

	@Test
	void knockoutMatchUsesTheSameSlotConflictCheck() {
		Venue v = data.venue();
		Long cup = cup(v, 3);
		service.start(v.manager(), cup);
		TournamentMatch m = all(cup).getFirst(); // 3 takım: 1 bay, 2-3 maçı
		staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(v.pitch().getId(),
				DAY.atTime(20, 0), 60, Channel.PHONE, null, "Müşteri", null, null));
		assertThatThrownBy(() -> service.schedule(v.manager(), m.getId(), v.pitch().getId(), DAY.atTime(20, 30), 60))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("dolu");
	}

	@Test
	void pagesRenderBracketAndPenaltyPicker() throws Exception {
		Venue v = data.venue();
		Long b = v.branch().getId();
		mvc.perform(post("/isletme/subeler/{b}/ligler", b).param("ad", "Bahar Kupası").param("bicim", "KNOCKOUT")
			.with(user(v.manager())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", containsString("güç sırasıyla")));
		Long cup = jdbc.queryForObject("select id from tournament where branch_id = ? and name = 'Bahar Kupası'",
				Long.class, b);
		for (String n : new String[] { "Alfa", "Beta", "Gama", "Delta", "Epsilon" }) {
			service.addEntry(v.manager(), cup, n);
		}
		mvc.perform(get("/isletme/ligler/{id}", cup).with(user(v.manager())))
			.andExpect(content().string(containsString("Eşleşmeleri oluştur")))
			.andExpect(content().string(containsString("güç sırasıyla")));
		service.start(v.manager(), cup);
		TournamentMatch m = all(cup).getFirst();
		play(v, m, 0, 0, m.getAwayEntryId());
		mvc.perform(get("/isletme/ligler/{id}", cup).with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Eşleşme ağacı")))
			.andExpect(content().string(containsString("Berabereyse penaltıları kazanan")))
			.andExpect(content().string(containsString("Penaltılarla")));
		mvc.perform(get("/ligler/{id}", cup)).andExpect(status().isOk())
			.andExpect(content().string(containsString("Çeyrek final")))
			.andExpect(content().string(containsString("Bay")));
		mvc.perform(get("/ligler")).andExpect(content().string(containsString("Eleme (kupa)")));
	}

}
