package com.sahahub.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.community.app.TeamMatchService;
import com.sahahub.community.app.TeamMatchService.Answer;
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

/** Takım maçları, katılım yanıtları, geçmiş/skor, logo. */
@IntegrationTest
@AutoConfigureMockMvc
class TeamMatchIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	TeamMatchService service;

	@Autowired
	TeamService teams;

	@Autowired
	TeamRepository teamRepo;

	@Autowired
	TeamMatchRepository matchRepo;

	@Autowired
	CustomerBookingService customer;

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

	record Squad(AppUserPrincipal captain, AppUserPrincipal p1, AppUserPrincipal p2, Long teamId) {
	}

	Squad squad() {
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal p1 = data.customer();
		AppUserPrincipal p2 = data.customer();
		Long t = teams.create(cap, "Takım", "İstanbul");
		String code = teamRepo.findById(t).orElseThrow().getInviteCode();
		teams.join(p1, code);
		teams.join(p2, code);
		return new Squad(cap, p1, p2, t);
	}

	Long freeMatch(Squad s) {
		return service.create(s.captain(), s.teamId(), null, DAY.atTime(20, 0), "Başka Tesis, Moda", "Rakip FK", null);
	}

	@Test
	void answersAreCountedAndChangeableUntilKickOff() {
		Squad s = squad();
		Long m = freeMatch(s);
		assertThat(jdbc.queryForObject("select count(*) from notification where kind = 'TEAM_MATCH_CREATED' and user_id in (?, ?, ?)",
				Integer.class, s.captain().id(), s.p1().id(), s.p2().id())).as("kaptan dışındaki üyelere").isEqualTo(2);

		service.answer(s.p1(), m, Answer.GOING);
		service.answer(s.p2(), m, Answer.MAYBE);
		service.answer(s.p2(), m, Answer.NOT_GOING); // değiştirilebilir
		TeamMatchService.MatchView v = service.forTeam(s.captain(), s.teamId()).upcoming().getFirst();
		assertThat(v.going()).isEqualTo(1);
		assertThat(v.maybe()).isZero();
		assertThat(v.notGoing()).isEqualTo(1);
		assertThat(v.noAnswer()).isEqualTo(1); // kaptan
		assertThat(v.attendees()).hasSize(2);
		assertThat(service.forTeam(s.p1(), s.teamId()).upcoming().getFirst().myAnswer()).isEqualTo(Answer.GOING);
		assertThat(service.myUpcoming(s.p2())).singleElement().satisfies(x -> assertThat(x.myAnswer()).isEqualTo(Answer.NOT_GOING));

		// Üye olmayan yanıt veremez, maçları göremez
		AppUserPrincipal outsider = data.customer();
		assertThatThrownBy(() -> service.answer(outsider, m, Answer.GOING)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.forTeam(outsider, s.teamId())).isInstanceOf(NotFoundException.class);
		// Oyuncu maç ekleyemez
		assertThatThrownBy(() -> service.create(s.p1(), s.teamId(), null, DAY.atTime(21, 0), "Yer", null, null))
			.isInstanceOf(BusinessRuleException.class);

		// Takımdan ayrılanın yanıtı sayılmaz
		teams.leave(s.p2(), s.teamId());
		assertThat(service.forTeam(s.captain(), s.teamId()).upcoming().getFirst().notGoing()).isZero();

		// Maç başladıktan sonra yanıt değişmez
		clock.set(DAY.atTime(20, 1).atZone(TestData.IST).toInstant());
		assertThatThrownBy(() -> service.answer(s.p1(), m, Answer.NOT_GOING)).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("başladı");
	}

	@Test
	void fromOwnReservation_cancelledWithIt_andNotTwice() {
		Venue v = data.venue();
		Squad s = squad();
		String code = customer.hold(s.captain(), v.pitch().getId(), DAY.atTime(21, 0).atZone(TestData.IST).toInstant());
		Long resId = jdbc.queryForObject("select id from reservation where code = ?", Long.class, code);
		assertThatThrownBy(() -> service.create(s.captain(), s.teamId(), resId, null, null, null, null))
			.as("onaylanmamış").isInstanceOf(BusinessRuleException.class);
		customer.confirm(s.captain(), code);
		Long m = service.create(s.captain(), s.teamId(), resId, null, null, "Şimşekler", null);
		TeamMatch tm = matchRepo.findById(m).orElseThrow();
		assertThat(tm.getPlace()).contains(v.pitch().getName());
		assertThat(tm.getStartsAt()).isEqualTo(DAY.atTime(21, 0).atZone(TestData.IST).toInstant());
		assertThatThrownBy(() -> service.create(s.captain(), s.teamId(), resId, null, null, null, null))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("zaten");
		// Başkasının rezervasyonu
		Squad other = squad();
		assertThatThrownBy(() -> service.create(other.captain(), other.teamId(), resId, null, null, null, null))
			.isInstanceOf(NotFoundException.class);

		customer.cancel(s.captain(), code);
		assertThat(matchRepo.findById(m).orElseThrow().getStatus()).isEqualTo(TeamMatch.Status.CANCELLED);
		assertThat(jdbc.queryForObject("select count(*) from notification where kind = 'TEAM_MATCH_CANCELLED' and user_id = ?",
				Integer.class, s.p1().id())).isEqualTo(1);
		assertThat(service.forTeam(s.p1(), s.teamId()).upcoming()).isEmpty();
	}

	@Test
	void historyAndScoreOnlyAfterKickOff_captainOnly() {
		Squad s = squad();
		Long m = freeMatch(s);
		service.answer(s.p1(), m, Answer.GOING);
		assertThatThrownBy(() -> service.recordScore(s.captain(), m, 3, 2)).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("başlamadan");
		assertThatThrownBy(() -> service.create(s.captain(), s.teamId(), null, DAY.minusDays(5).atTime(20, 0), "Yer",
				null, null)).isInstanceOf(BusinessRuleException.class);

		clock.advance(Duration.ofDays(2));
		assertThatThrownBy(() -> service.recordScore(s.p1(), m, 3, 2)).isInstanceOf(BusinessRuleException.class);
		service.recordScore(s.captain(), m, 3, 2);
		TeamMatchService.TeamMatches tm = service.forTeam(s.p1(), s.teamId());
		assertThat(tm.upcoming()).isEmpty();
		assertThat(tm.history()).singleElement().satisfies(x -> {
			assertThat(x.ourScore()).isEqualTo(3);
			assertThat(x.going()).isEqualTo(1);
			assertThat(x.canScore()).as("oyuncu düzeltemez").isFalse();
		});
		assertThat(service.forTeam(s.captain(), s.teamId()).history().getFirst().canScore()).isTrue();
	}

	@Test
	void reminder24HoursBefore_notToNotGoing_once() {
		Squad s = squad();
		Long m = freeMatch(s); // 3 Mart 20:00; şimdi 2 Mart 09:00 (35 saat önce)
		Long cancelled = service.create(s.captain(), s.teamId(), null, DAY.atTime(19, 0), "Yer", null, null);
		service.cancel(s.captain(), cancelled);
		service.answer(s.p1(), m, Answer.GOING);
		service.answer(s.p2(), m, Answer.NOT_GOING);

		service.enqueueReminders();
		assertThat(reminders(m)).as("24 saatten önce gitmez").isZero();

		clock.set(DAY.atTime(8, 0).atZone(TestData.IST).toInstant());
		Long late = service.create(s.captain(), s.teamId(), null, DAY.atTime(21, 0), "Yer", null, null);
		service.enqueueReminders();
		service.enqueueReminders(); // görev tekrar çalışır; ikinci hatırlatma oluşmaz
		assertThat(jdbc.queryForList("select user_id from notification where dedup_key like ?", Long.class,
				"team-match-reminder:" + m + ":%"))
			.as("kaptan (yanıtsız) ve p1 (geliyor); p2 gelmiyor dedi").containsExactlyInAnyOrder(s.captain().id(), s.p1().id());
		assertThat(jdbc.queryForObject("select body from notification where dedup_key = ?", String.class,
				"team-match-reminder:" + m + ":" + s.captain().id()))
			.contains("Rakip FK", "1 geliyor, 0 kararsız, 1 yanıt vermedi", "Henüz yanıt vermediniz");
		assertThat(jdbc.queryForObject("select count(*) from notification_outbox where dedup_key like ?", Integer.class,
				"team-match-reminder:" + m + ":%")).as("e-posta tercihi açık üyelere outbox satırı").isPositive();
		assertThat(reminders(cancelled)).as("iptal edilen maç").isZero();
		assertThat(reminders(late)).as("24 saatten az kala eklenen maç: ekleme bildirimi yeterli").isZero();

		// Maç başladıktan sonra hatırlatma yok
		Squad t = squad();
		clock.set(DAY.atTime(8, 0).atZone(TestData.IST).toInstant().minus(Duration.ofDays(2)));
		Long past = service.create(t.captain(), t.teamId(), null, DAY.atTime(7, 0), "Yer", null, null);
		clock.set(DAY.atTime(8, 0).atZone(TestData.IST).toInstant());
		service.enqueueReminders();
		assertThat(reminders(past)).isZero();
	}

	int reminders(Long matchId) {
		return jdbc.queryForObject("select count(*) from notification where dedup_key like ?", Integer.class,
				"team-match-reminder:" + matchId + ":%");
	}

	@Test
	void pagesAndLogo() throws Exception {
		Squad s = squad();
		Long m = freeMatch(s);
		mvc.perform(get("/takimlar/{id}", s.teamId()).with(user(s.captain()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Maç ekle")))
			.andExpect(content().string(containsString("Rakip FK")));
		mvc.perform(post("/takim-maclari/{m}/yanit", m).param("cevap", "GOING").param("takim", s.teamId().toString())
			.with(user(s.p1())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Yanıtınız kaydedildi: Geliyorum."));
		mvc.perform(get("/takimlar/{id}", s.teamId()).with(user(s.p1()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("1 geliyor")))
			.andExpect(content().string(containsString("aria-pressed=\"true\"")))
			.andExpect(content().string(not(containsString("Maç ekle"))));
		mvc.perform(get("/takimlar").with(user(s.p2()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Yaklaşan maçlarım")));

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", png);
		mvc.perform(multipart("/takimlar/{id}/logo", s.teamId())
			.file(new MockMultipartFile("dosya", "logo.png", "image/png", png.toByteArray()))
			.with(user(s.p1())).with(csrf()))
			.andExpect(flash().attribute("flashError", "Bu işlemi yalnızca takım kaptanı yapabilir."));
		mvc.perform(multipart("/takimlar/{id}/logo", s.teamId())
			.file(new MockMultipartFile("dosya", "logo.png", "image/png", png.toByteArray()))
			.with(user(s.captain())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Logo yüklendi."));
		mvc.perform(get("/takim-logo/{id}", s.teamId())).andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"));
		mvc.perform(post("/takimlar/{id}/aciklama", s.teamId()).param("aciklama", "Salı akşamları oynarız.")
			.with(user(s.captain())).with(csrf())).andExpect(status().is3xxRedirection());
		mvc.perform(get("/takimlar/{id}", s.teamId()).with(user(s.p1())))
			.andExpect(content().string(containsString("Salı akşamları oynarız.")))
			.andExpect(content().string(containsString("/takim-logo/" + s.teamId())));
		teams.disband(s.captain(), s.teamId());
		mvc.perform(get("/takim-logo/{id}", s.teamId())).andExpect(status().isNotFound());
	}

}
