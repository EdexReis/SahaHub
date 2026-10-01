package com.sahahub.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Aşama 4 ekranları: düzenli rezervasyon, bekleme listesi, bildirimler, tercihler, demo mesaj kutusu. */
@IntegrationTest
@AutoConfigureMockMvc
class SeriesWaitlistPagesRenderIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	StaffReservationService staff;

	@Autowired
	ReservationRepository reservations;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	MockHttpServletRequestBuilder seriesForm(MockHttpServletRequestBuilder b, Venue v) {
		return b.param("pitchId", v.pitch().getId().toString())
			.param("date", DAY.toString())
			.param("time", "20:00")
			.param("durationMinutes", "60")
			.param("occurrences", "4")
			.param("channel", "PHONE")
			.param("guestName", "Salı Takımı")
			.with(user(v.reception()))
			.with(csrf());
	}

	@Test
	void seriesPreviewCreateAndPanel() throws Exception {
		Venue v = data.venue();
		Long b = v.branch().getId();
		staff.create(v.reception(), b, new StaffReservationService.CreateCommand(v.pitch().getId(),
				DAY.plusWeeks(1).atTime(20, 0), 60, Channel.PHONE, null, "Engel", null, null));

		mvc.perform(get("/isletme/subeler/{b}/duzenli", b).with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Tarihleri önizle")));

		// Önizleme: bir tarih dolu → "Tümünü oluştur" pasif, dolu tarih işaretli
		mvc.perform(seriesForm(post("/isletme/subeler/{b}/duzenli/onizleme", b), v))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("3 / 4 tarih uygun.")))
			.andExpect(content().string(containsString("Başka rezervasyon var")))
			.andExpect(content().string(containsString("disabled=\"disabled\">Tümünü oluştur (4)")));

		// "Tümü" reddedilir, form hata mesajıyla geri gelir; hiçbir maç oluşmaz
		mvc.perform(seriesForm(post("/isletme/subeler/{b}/duzenli", b), v).param("mod", "ALL"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Uygun olmayan tarihler var")));

		mvc.perform(seriesForm(post("/isletme/subeler/{b}/duzenli", b), v).param("mod", "SELECTED")
			.param("chosen", DAY.toString(), DAY.plusWeeks(2).toString(), DAY.plusWeeks(3).toString()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("flashSuccess", "Düzenli rezervasyon oluşturuldu."));

		String code = reservations.findAll().stream()
			.filter(r -> r.getPitchId().equals(v.pitch().getId()) && r.getSeriesIndex() != null
					&& r.getSeriesIndex() == 1)
			.findFirst().orElseThrow().getCode();
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).param("tarih", DAY.toString()).param("secili", code)
			.with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Her hafta · 1. maç / 4")))
			.andExpect(content().string(containsString("Bu ve sonraki 2 maçı iptal et")));
		mvc.perform(post("/isletme/rezervasyonlar/{c}/seri-iptal", code).param("reason", "Sezon bitti")
			.with(user(v.reception())).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("flashSuccess", "3 maç iptal edildi. Saatler boşa çıktı."));
	}

	@Test
	void customerJoinsWaitlistFromTakenSlot() throws Exception {
		Venue v = data.venue();
		staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(v.pitch().getId(),
				DAY.atTime(20, 0), 60, Channel.PHONE, null, "Dolu", null, null));
		AppUserPrincipal me = data.customer();
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Sıraya gir")));
		// Giriş yapmamış kullanıcı için düğme yok
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()))
			.andExpect(content().string(not(containsString("Sıraya gir"))));

		String start = DAY.atTime(20, 0).atZone(TestData.IST).toInstant().toString();
		mvc.perform(post("/bekleme-listesi").param("pitchId", v.pitch().getId().toString()).param("baslangic", start)
			.with(user(me)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/rezervasyonlarim").with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Bekleme listem")))
			.andExpect(content().string(containsString("1. sıradasınız")))
			.andExpect(content().string(containsString("Sıradan çık")));
	}

	@Test
	void notificationPagesAndPreferences() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal me = data.customer();
		staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(v.pitch().getId(),
				DAY.atTime(20, 0), 60, Channel.PHONE, me.email(), null, null, null));

		mvc.perform(get("/sahalar").with(user(me)))
			.andExpect(content().string(containsString("Bildirimler, 1 okunmamış")));
		mvc.perform(get("/bildirimler").with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Rezervasyonunuz onaylandı")))
			.andExpect(content().string(containsString("Tümünü okundu say")));
		mvc.perform(post("/bildirimler/okundu").with(user(me)).with(csrf())).andExpect(status().is3xxRedirection());
		mvc.perform(get("/bildirimler").with(user(me)))
			.andExpect(content().string(not(containsString("Tümünü okundu say"))));

		mvc.perform(get("/profil").with(user(me))).andExpect(status().isOk())
			.andExpect(content().string(containsString("SMS bu sürümde demo kanaldır")));
		// SMS seçili ama telefon yok → hata, tercih değişmez
		mvc.perform(post("/profil/bildirimler").param("eposta", "true").param("sms", "true").param("telefon", "")
			.with(user(me)).with(csrf()))
			.andExpect(flash().attribute("flashError", "SMS bildirimi için telefon numarası gerekli."));
		mvc.perform(post("/profil/bildirimler").param("sms", "true").param("telefon", "0555 111 22 33")
			.with(user(me)).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Bildirim tercihleriniz kaydedildi."));
	}

	@Test
	void demoMessageBoxIsAdminOnly() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal admin = new AppUserPrincipal(-1L, "admin@test.local", "Yönetici", "x", true, true, false);
		mvc.perform(get("/admin/demo-mesajlar").with(user(admin)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("hiçbiri gerçek bir telefona gönderilmedi")));
		mvc.perform(get("/admin/demo-mesajlar").with(user(v.owner()))).andExpect(status().isForbidden());
	}

}
