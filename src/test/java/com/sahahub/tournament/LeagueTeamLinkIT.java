package com.sahahub.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.community.app.TeamMatchService;
import com.sahahub.community.app.TeamService;
import com.sahahub.community.domain.TeamMatch;
import com.sahahub.community.domain.TeamMatchRepository;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
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

/** Lig/kupa kaydını platform takımına bağlama ve lig maçlarının takım sayfasına yansıması. */
@IntegrationTest
@AutoConfigureMockMvc
class LeagueTeamLinkIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	TournamentService service;

	@Autowired
	TournamentQueries queries;

	@Autowired
	TournamentEntryRepository entries;

	@Autowired
	TournamentMatchRepository matches;

	@Autowired
	TeamService teams;

	@Autowired
	TeamRepository teamRepo;

	@Autowired
	TeamMatchService teamMatches;

	@Autowired
	TeamMatchRepository teamMatchRepo;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	record Squad(AppUserPrincipal captain, AppUserPrincipal player, Long teamId) {
	}

	Squad squad(String name) {
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal p = data.customer();
		Long t = teams.create(cap, name, "İstanbul");
		teams.join(p, teamRepo.findById(t).orElseThrow().getInviteCode());
		return new Squad(cap, p, t);
	}

	TournamentEntry entry(Long tournamentId, String name) {
		return entries.findByTournamentIdOrderById(tournamentId).stream().filter(e -> e.getName().equals(name))
			.findFirst().orElseThrow();
	}

	List<TournamentMatch> matchesOf(Long tournamentId, Long entryId) {
		return matches.findByTournamentIdOrderByRoundAscIdAsc(tournamentId).stream()
			.filter(m -> m.getHomeEntryId().equals(entryId) || m.getAwayEntryId().equals(entryId))
			.toList();
	}

	int notifications(AppUserPrincipal u, String kind) {
		return jdbc.queryForObject("select count(*) from notification where user_id = ? and kind = ?", Integer.class,
				u.id(), kind);
	}

	@Test
	void linkedLeagueMatchesFollowSchedule_rsvpWorks_captainCannotOverride_unlinkCleansUp() {
		Venue v = data.venue();
		Long lig = service.create(v.manager(), v.branch().getId(), "Lig", false, 3, 1, 0);
		for (String n : List.of("Alfa", "Beta", "Gama")) {
			service.addEntry(v.manager(), lig, n);
		}
		Squad s = squad("Alfa FK");
		String codeA = entry(lig, "Alfa").getLinkCode();
		assertThatThrownBy(() -> service.linkTeam(s.player(), codeA, s.teamId())).as("kaptan değil")
			.isInstanceOf(NotFoundException.class);
		service.start(v.manager(), lig);
		Long alfa = entry(lig, "Alfa").getId();
		List<TournamentMatch> mine = matchesOf(lig, alfa); // tek devre, 3 takım: Alfa'nın 2 maçı
		TournamentMatch m1 = mine.get(0);
		service.schedule(v.manager(), m1.getId(), v.pitch().getId(), DAY.atTime(20, 0), 60);

		// Planlandıktan sonra bağlanır: maç takım sayfasına gelir, üyelere bildirim
		service.linkTeam(s.captain(), codeA, s.teamId());
		TeamMatchService.MatchView up = teamMatches.forTeam(s.player(), s.teamId()).upcoming().getFirst();
		String rival = entries.findById(m1.getHomeEntryId().equals(alfa) ? m1.getAwayEntryId() : m1.getHomeEntryId())
			.orElseThrow().getName();
		assertThat(up.league()).isTrue();
		assertThat(up.opponent()).isEqualTo(rival);
		assertThat(up.place()).contains(v.pitch().getName());
		assertThat(up.note()).isEqualTo("Lig · " + m1.getRound() + ". hafta");
		assertThat(notifications(s.player(), "TEAM_LEAGUE_MATCH")).isEqualTo(1);

		// Aynı takım aynı ligde ikinci kayda bağlanamaz; bağlı kayıt başkasınca alınamaz
		assertThatThrownBy(() -> service.linkTeam(s.captain(), entry(lig, "Beta").getLinkCode(), s.teamId()))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("başka bir kayda");
		Squad other = squad("Başka FK");
		assertThatThrownBy(() -> service.linkTeam(other.captain(), codeA, other.teamId()))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("zaten");

		// Kaptan lig maçını iptal edemez, skorunu giremez; katılım yanıtı çalışır
		assertThatThrownBy(() -> teamMatches.cancel(s.captain(), up.id())).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("lig maçı");
		teamMatches.answer(s.player(), up.id(), TeamMatchService.Answer.GOING);

		// Yeniden planlama: aynı kayıt güncellenir, yanıt korunur, "saati değişti" bildirimi
		service.schedule(v.manager(), m1.getId(), v.pitch().getId(), DAY.atTime(21, 0), 60);
		TeamMatchService.MatchView moved = teamMatches.forTeam(s.player(), s.teamId()).upcoming().getFirst();
		assertThat(moved.id()).isEqualTo(up.id());
		assertThat(moved.startsAt()).isEqualTo(DAY.atTime(21, 0).atZone(TestData.IST).toInstant());
		assertThat(moved.myAnswer()).isEqualTo(TeamMatchService.Answer.GOING);
		assertThat(notifications(s.player(), "TEAM_LEAGUE_MATCH_MOVED")).isEqualTo(1);

		// Planı kaldırılınca iptal; yeniden planlanınca yeni kopya
		service.unschedule(v.manager(), m1.getId());
		assertThat(teamMatchRepo.findById(up.id()).orElseThrow().getStatus()).isEqualTo(TeamMatch.Status.CANCELLED);
		assertThat(teamMatches.forTeam(s.player(), s.teamId()).upcoming()).isEmpty();
		assertThat(notifications(s.player(), "TEAM_MATCH_CANCELLED")).isEqualTo(1);
		service.schedule(v.manager(), m1.getId(), v.pitch().getId(), DAY.atTime(20, 0), 60);
		Long again = teamMatches.forTeam(s.player(), s.teamId()).upcoming().getFirst().id();
		assertThat(again).isNotEqualTo(up.id());

		// Sonuç ligde girilir; takım tarafında kendi bakış açısıyla skor
		clock.set(DAY.atTime(21, 30).atZone(TestData.IST).toInstant());
		service.recordResult(v.manager(), m1.getId(), 1, 3);
		boolean home = m1.getHomeEntryId().equals(alfa);
		TeamMatchService.MatchView played = teamMatches.forTeam(s.captain(), s.teamId()).history().getFirst();
		assertThat(played.ourScore()).isEqualTo(home ? 1 : 3);
		assertThat(played.theirScore()).isEqualTo(home ? 3 : 1);
		assertThat(played.canScore()).as("kaptan lig skorunu değiştiremez").isFalse();
		assertThatThrownBy(() -> teamMatches.recordScore(s.captain(), again, 5, 0))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(queries.forTeam(s.teamId())).singleElement()
			.satisfies(r -> assertThat(r.summary()).contains("sıra").contains("1 maç"));

		// Bağlantı kaldırılınca: planlı lig maçları düşer, oynanmış kalır, eski kod çalışmaz
		TournamentMatch m2 = mine.get(1);
		service.schedule(v.manager(), m2.getId(), v.pitch().getId(), DAY.plusDays(1).atTime(20, 0), 60);
		assertThat(teamMatches.forTeam(s.player(), s.teamId()).upcoming()).hasSize(1);
		service.unlinkTeam(v.manager(), lig, alfa);
		assertThat(teamMatches.forTeam(s.player(), s.teamId()).upcoming()).isEmpty();
		assertThat(teamMatches.forTeam(s.player(), s.teamId()).history()).hasSize(1);
		assertThatThrownBy(() -> service.linkTeam(s.captain(), codeA, s.teamId())).isInstanceOf(NotFoundException.class);
		assertThat(entry(lig, "Alfa").getLinkCode()).isNotEqualTo(codeA);
		assertThat(queries.forTeam(s.teamId())).isEmpty();
	}

	@Test
	void knockoutCorrectionMovesTheNextMatchToTheOtherTeam() {
		Venue v = data.venue();
		Long cup = service.create(v.manager(), v.branch().getId(), "Kupa", Tournament.Format.KNOCKOUT, false, 3, 1, 0);
		for (int i = 1; i <= 4; i++) {
			service.addEntry(v.manager(), cup, "T" + i);
		}
		Squad x = squad("X FK");
		service.linkTeam(x.captain(), entry(cup, "T1").getLinkCode(), x.teamId());
		service.start(v.manager(), cup); // 1-4, 2-3
		List<TournamentMatch> semis = matches.findByTournamentIdOrderByRoundAscIdAsc(cup);
		service.schedule(v.manager(), semis.get(0).getId(), v.pitch().getId(), DAY.atTime(9, 0), 60);
		service.schedule(v.manager(), semis.get(1).getId(), v.pitch().getId(), DAY.atTime(10, 0), 60);
		clock.set(DAY.atTime(11, 30).atZone(TestData.IST).toInstant());
		service.recordResult(v.manager(), semis.get(0).getId(), 2, 0); // T1
		service.recordResult(v.manager(), semis.get(1).getId(), 1, 0); // T2
		TournamentMatch fin = matches.findByTournamentIdOrderByRoundAscIdAsc(cup).stream()
			.filter(m -> m.getRound() == 2).findFirst().orElseThrow();
		service.schedule(v.manager(), fin.getId(), v.pitch().getId(), DAY.plusDays(1).atTime(20, 0), 60);
		TeamMatchService.MatchView f = teamMatches.forTeam(x.captain(), x.teamId()).upcoming().getFirst();
		assertThat(f.note()).isEqualTo("Kupa · Final");
		assertThat(f.opponent()).isEqualTo("T2");

		// Final oynanmadan yarı final düzeltildi: T4 finale çıkar, X'in final kopyası iptal olur
		service.recordResult(v.manager(), semis.get(0).getId(), 0, 2);
		assertThat(teamMatchRepo.findById(f.id()).orElseThrow().getStatus()).isEqualTo(TeamMatch.Status.CANCELLED);
		assertThat(teamMatches.forTeam(x.captain(), x.teamId()).upcoming()).isEmpty();
		assertThat(teamMatches.forTeam(x.captain(), x.teamId()).history()).singleElement()
			.satisfies(h -> {
				assertThat(h.ourScore()).isZero();
				assertThat(h.theirScore()).isEqualTo(2);
			});
		assertThat(queries.forTeam(x.teamId()).getFirst().summary()).isEqualTo("Elendi");
	}

	@Test
	void pages_codeNeverOnPublicPage() throws Exception {
		Venue v = data.venue();
		Long lig = service.create(v.manager(), v.branch().getId(), "Bahar Ligi", false, 3, 1, 0);
		for (String n : List.of("Alfa", "Beta", "Gama")) {
			service.addEntry(v.manager(), lig, n);
		}
		String code = entry(lig, "Alfa").getLinkCode();
		mvc.perform(get("/isletme/ligler/{id}", lig).with(user(v.manager())))
			.andExpect(content().string(containsString("/lig-davet/" + code)))
			.andExpect(content().string(containsString("0/3 bağlı")));
		service.start(v.manager(), lig);
		mvc.perform(get("/ligler/{id}", lig)).andExpect(status().isOk())
			.andExpect(content().string(not(containsString(code))))
			.andExpect(content().string(not(containsString("lig-davet"))));

		mvc.perform(get("/lig-davet/{c}", code)).andExpect(status().is3xxRedirection()); // giriş gerekli
		Squad s = squad("Alfa FK");
		mvc.perform(get("/lig-davet/{c}", code).with(user(s.captain()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Takımımı bağla")))
			.andExpect(content().string(containsString("Alfa FK")));
		mvc.perform(get("/lig-davet/{c}", code).with(user(s.player()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("kaptanı olmalısınız")));
		mvc.perform(get("/lig-davet/{c}", "YANLISKOD123").with(user(s.captain()))).andExpect(status().isNotFound());
		mvc.perform(post("/lig-davet/{c}", code).param("takim", s.teamId().toString()).with(user(s.captain()))
			.with(csrf())).andExpect(redirectedUrl("/takimlar/" + s.teamId()));
		mvc.perform(get("/takimlar/{id}", s.teamId()).with(user(s.player())))
			.andExpect(content().string(containsString("Lig ve turnuvalar")))
			.andExpect(content().string(containsString("Bahar Ligi")))
			.andExpect(content().string(containsString("kayıt adı: Alfa")));
		mvc.perform(get("/isletme/ligler/{id}", lig).with(user(v.manager())))
			.andExpect(content().string(containsString("Bağlı: Alfa FK")))
			.andExpect(content().string(containsString("Bağlantıyı kaldır")));
		mvc.perform(get("/lig-davet/{c}", code).with(user(s.captain())))
			.andExpect(content().string(containsString("zaten bir takıma bağlı")));
	}

}
