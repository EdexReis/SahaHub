package com.sahahub.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/**
 * Her önemli sayfanın sunucuda hatasız render edildiğini (Thymeleaf ifadeleri dahil) tarayıcı
 * gerektirmeden doğrular. Şablon hataları normal test çalıştırmasında yakalanır.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PagesRenderIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	CustomerBookingService customer;

	@Autowired
	StaffReservationService staff;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	@Test
	void publicPages() throws Exception {
		Venue v = data.venue();
		mvc.perform(get("/sahalar")).andExpect(status().isOk()).andExpect(content().string(containsString("Saha seç")));
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("class=\"slot\"")))
			.andExpect(content().string(containsString("Gece yarısından sonra")));
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()).header("HX-Request", "true"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("id=\"slot-area\"")));
		mvc.perform(get("/giris")).andExpect(status().isOk());
		mvc.perform(get("/kayit")).andExpect(status().isOk());
		mvc.perform(get("/sahalar/999999")).andExpect(status().isNotFound());
	}

	@Test
	void customerPages() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal me = data.customer();
		mvc.perform(get("/sahalar/{id}", v.pitch().getId()).param("tarih", DAY.toString()).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("saati tut")));
		String start = DAY.atTime(21, 0).atZone(TestData.IST).toInstant().toString();
		mvc.perform(get("/sahalar/{id}/sec", v.pitch().getId()).param("baslangic", start).with(user(me)))
			.andExpect(status().isOk());
		String code = customer.hold(me, v.pitch().getId(), DAY.atTime(21, 0).atZone(TestData.IST).toInstant());
		mvc.perform(get("/rezervasyon/{c}", code).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Rezervasyonu onayla")));
		mvc.perform(get("/rezervasyonlarim").with(user(me))).andExpect(status().isOk());
		mvc.perform(post("/rezervasyon/{c}/onayla", code).with(user(me)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/rezervasyon/{c}", code).with(user(me)))
			.andExpect(content().string(containsString("Onaylandı")));
	}

	@Test
	void staffPages() throws Exception {
		Venue v = data.venue(15, 60, 75);
		String code = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Çok Uzun İsimli Misafir Müşteri", null,
				null));
		Long b = v.branch().getId();
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).param("tarih", DAY.toString()).with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("ev-free")))
			.andExpect(content().string(containsString("Çok Uzun İsimli Misafir Müşteri")));
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).param("tarih", DAY.toString()).param("gorunum", "hafta")
			.with(user(v.reception()))).andExpect(status().isOk());
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).param("tarih", DAY.toString()).param("secili", code)
			.with(user(v.reception()))).andExpect(status().isOk()).andExpect(content().string(containsString("Başka saate taşı")));
		mvc.perform(get("/isletme/rezervasyonlar/{c}", code).header("HX-Request", "true").with(user(v.reception())))
			.andExpect(status().isOk());
		mvc.perform(get("/isletme/subeler/{b}/hizli-rezervasyon", b).param("saha", v.pitch().getId().toString())
			.param("baslangic", DAY.atTime(21, 15).toString()).header("HX-Request", "true").with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Hızlı rezervasyon")));
		mvc.perform(get("/isletme/subeler/{b}/kapatma", b).with(user(v.manager()))).andExpect(status().isOk());
		mvc.perform(get("/isletme").with(user(v.reception()))).andExpect(status().is3xxRedirection());
	}

	@Test
	void htmxQuickBookingRedirectsToBusinessDayWithFlash() throws Exception {
		Venue v = data.venue();
		mvc.perform(post("/isletme/subeler/{b}/rezervasyonlar", v.branch().getId())
			.param("pitchId", v.pitch().getId().toString())
			.param("date", DAY.plusDays(1).toString())
			.param("time", "00:00")
			.param("durationMinutes", "60")
			.param("channel", "PHONE")
			.param("guestName", "Gece Maçı")
			.header("HX-Request", "true")
			.with(user(v.reception()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(header().string("HX-Redirect", containsString("tarih=" + DAY)));
	}

	@Test
	void availabilityApiReturnsDtoAndProblemDetail() throws Exception {
		Venue v = data.venue();
		mvc.perform(get("/api/sahalar/{id}/uygunluk", v.pitch().getId()).param("tarih", DAY.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.slots[0].state").value("AVAILABLE"));
		mvc.perform(get("/api/sahalar/{id}/uygunluk", 999999).param("tarih", DAY.toString()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Saha bulunamadı."));
	}

	@Test
	void errorPageDoesNotLeakDetailsOrShowFrameworkMessage() throws Exception {
		mvc.perform(get("/sahalar/999999"))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("Saha bulunamadı.")));
	}

}
