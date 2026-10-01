package com.sahahub.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.community.app.ListingService;
import com.sahahub.community.app.TeamService;
import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;
import com.sahahub.tournament.app.TournamentService;

/** Aşama 5 ekranları: takımlar, davet, ilanlar, başvurular, ligler (personel ve herkese açık), takvimde maç. */
@IntegrationTest
@AutoConfigureMockMvc
class CommunityLeaguePagesRenderIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	TeamService teams;

	@Autowired
	TeamRepository teamRepo;

	@Autowired
	ListingService listings;

	@Autowired
	TournamentService tournaments;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	@Test
	void teamPagesAndInvite() throws Exception {
		AppUserPrincipal cap = data.customer();
		AppUserPrincipal friend = data.customer();
		mvc.perform(get("/takimlar").with(user(cap))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Takımı kur")));
		mvc.perform(post("/takimlar").param("ad", "Render Kartalları").param("sehir", "İstanbul").with(user(cap)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/takimlar/*"));
		Long t = teams.myTeams(cap).getFirst().id();
		String code = teamRepo.findById(t).orElseThrow().getInviteCode();
		mvc.perform(get("/takimlar/{id}", t).with(user(cap))).andExpect(status().isOk())
			.andExpect(content().string(containsString("/davet/" + code)))
			.andExpect(content().string(containsString("Davet bağlantısı")));

		// Davet önizlemesi herkese açık; katılmak giriş ister
		mvc.perform(get("/davet/{c}", code)).andExpect(status().isOk())
			.andExpect(content().string(containsString("giriş yapın")));
		mvc.perform(get("/davet/{c}", code).with(user(friend))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Takıma katıl")));
		mvc.perform(post("/davet/{c}", code).with(user(friend)).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Takıma katıldınız."));
		mvc.perform(get("/takimlar/{id}", t).with(user(friend))).andExpect(status().isOk())
			.andExpect(content().string(not(containsString("/davet/" + code))));
		// Üye olmayan takım sayfasını göremez
		mvc.perform(get("/takimlar/{id}", t).with(user(data.customer()))).andExpect(status().isNotFound());
		mvc.perform(get("/davet/YANLISKOD")).andExpect(status().isNotFound());
	}

	@Test
	void listingPages() throws Exception {
		AppUserPrincipal cap = data.customer();
		Long t = teams.create(cap, "İlan Takımı", "İstanbul");
		mvc.perform(get("/ilanlar/yeni").with(user(cap))).andExpect(status().isOk())
			.andExpect(content().string(containsString("İlanı yayınla")));
		mvc.perform(get("/ilanlar/yeni").with(user(data.customer()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Önce bir takım kurun")));
		mvc.perform(post("/ilanlar").param("kind", "PLAYERS_WANTED").param("teamId", t.toString())
			.param("city", "İstanbul").param("playersNeeded", "0").param("level", "CASUAL")
			.with(user(cap)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Kaç oyuncu aradığınızı yazın")));
		mvc.perform(post("/ilanlar").param("kind", "PLAYERS_WANTED").param("teamId", t.toString())
			.param("city", "İstanbul").param("district", "Moda").param("playAt", DAY.atTime(21, 0).toString())
			.param("playersNeeded", "2").param("level", "CASUAL").param("note", "Kaleci arıyoruz")
			.with(user(cap)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/ilanlar/*"));

		mvc.perform(get("/ilanlar")).andExpect(status().isOk())
			.andExpect(content().string(containsString("İlan Takımı")))
			.andExpect(content().string(containsString("oyuncu aranıyor")));
		mvc.perform(get("/ilanlar").param("tur", "OPPONENT_WANTED")).andExpect(status().isOk())
			.andExpect(content().string(not(containsString("İlan Takımı"))));

		Long id = listings.create(cap, new ListingService.CreateCommand(Listing.Kind.OPPONENT_WANTED, t, null,
				"İstanbul", null, null, null, Listing.Level.COMPETITIVE, null));
		mvc.perform(get("/ilanlar/{id}", id)).andExpect(status().isOk())
			.andExpect(content().string(containsString("Başvurmak için")));
		AppUserPrincipal noTeam = data.customer();
		mvc.perform(get("/ilanlar/{id}", id).with(user(noTeam))).andExpect(status().isOk())
			.andExpect(content().string(containsString("kaptanı olduğunuz bir takım gerekir")));

		AppUserPrincipal rival = data.customer();
		Long rt = teams.create(rival, "Rakip FK", "İstanbul");
		mvc.perform(get("/ilanlar/{id}", id).with(user(rival))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Rakip olarak başvur")));
		mvc.perform(post("/ilanlar/{id}/basvur", id).param("takim", rt.toString()).param("mesaj", "Hazırız")
			.with(user(rival)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/ilanlar/{id}", id).with(user(cap))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Rakip FK")))
			.andExpect(content().string(containsString("Kabul et")));
		mvc.perform(get("/ilanlarim").with(user(rival))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Yanıt bekliyor")));
	}

	@Test
	void leaguePagesAndCalendar() throws Exception {
		Venue v = data.venue();
		Long b = v.branch().getId();
		mvc.perform(get("/isletme/subeler/{b}/ligler", b).with(user(v.manager()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Yeni lig veya turnuva")));
		mvc.perform(get("/isletme/subeler/{b}/ligler", b).with(user(v.reception()))).andExpect(status().isForbidden());

		Long id = tournaments.create(v.manager(), b, "Render Ligi", false, 3, 1, 0);
		mvc.perform(get("/isletme/ligler/{id}", id).with(user(v.manager()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Fikstürü oluştur")));
		mvc.perform(get("/ligler/{id}", id)).andExpect(status().isNotFound()); // taslak herkese kapalı
		for (String n : new String[] { "Alfa", "Beta", "Gama", "Delta" }) {
			tournaments.addEntry(v.manager(), id, n);
		}
		mvc.perform(post("/isletme/ligler/{id}/baslat", id).with(user(v.manager())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Fikstür oluşturuldu: 6 maç. Şimdi maçları planlayın."));
		tournaments.planWeekly(v.manager(), id, DAY, LocalTime.of(20, 0), 60, v.pitch().getId());

		mvc.perform(get("/isletme/ligler/{id}", id).with(user(v.manager()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Puan durumu")))
			.andExpect(content().string(containsString("Saati değiştir")));
		mvc.perform(get("/ligler")).andExpect(status().isOk())
			.andExpect(content().string(containsString("Render Ligi")));
		mvc.perform(get("/ligler/{id}", id)).andExpect(status().isOk())
			.andExpect(content().string(containsString("1. hafta")))
			.andExpect(content().string(containsString("Alfa")));

		// Personel takviminde maç görünür (resepsiyon dahil)
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).param("tarih", DAY.toString()).with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("ev-match")))
			.andExpect(content().string(containsString("Render Ligi · 1. hafta")));
		// Müşteri ekranında maç saati dolu görünür
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()).with(user(data.customer())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("20:00 ile 21:00 arası dolu")));
	}

}
