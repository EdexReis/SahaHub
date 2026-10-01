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

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Aşama 6 yönetim ekranları sunucuda hatasız render edilir ve formlar uçtan uca çalışır. */
@IntegrationTest
@AutoConfigureMockMvc
class AdminPagesRenderIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	StaffReservationService staff;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	@Test
	void branchSettingsAndPitchForms() throws Exception {
		Venue v = data.venue();
		Long b = v.branch().getId();
		mvc.perform(get("/isletme/subeler/{b}/ayarlar", b).with(user(v.manager()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Haftalık çalışma saatleri")))
			.andExpect(content().string(containsString("Fotoğraf yok")));
		mvc.perform(get("/isletme/subeler/{b}/ayarlar", b).with(user(v.reception()))).andExpect(status().isForbidden());

		var week = post("/isletme/subeler/{b}/ayarlar/saatler", b).with(user(v.manager())).with(csrf());
		for (String d : new String[] { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY" }) {
			week.param("acilis_" + d, "10:00").param("kapanis_" + d, "00:00");
		}
		week.param("kapali_SATURDAY", "1").param("kapali_SUNDAY", "1");
		mvc.perform(week).andExpect(flash().attribute("flashSuccess", "Çalışma saatleri kaydedildi."));
		mvc.perform(get("/isletme/subeler/{b}/ayarlar", b).with(user(v.manager())))
			.andExpect(content().string(containsString("value=\"10:00\"")));

		mvc.perform(post("/isletme/subeler/{b}/ayarlar/ozel-gunler", b).param("tarih", DAY.plusDays(2).toString())
			.param("kapali", "1").param("not", "Bayram").with(user(v.manager())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Özel gün eklendi."));

		mvc.perform(get("/isletme/subeler/{b}/sahalar/yeni", b).with(user(v.manager()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Sahayı ekle")));
		mvc.perform(post("/isletme/subeler/{b}/sahalar", b).param("name", "").param("surface", "ARTIFICIAL_TURF")
			.param("capacityPlayers", "14").param("slotMinutes", "60").param("slotStepMinutes", "60")
			.param("bufferMinutes", "0").param("basePrice", "1.250,50").with(user(v.manager())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Saha adı 1-80 karakter olmalı.")));
		mvc.perform(post("/isletme/subeler/{b}/sahalar", b).param("name", "Gece Sahası").param("surface", "PARQUET")
			.param("capacityPlayers", "10").param("slotMinutes", "60").param("slotStepMinutes", "60")
			.param("bufferMinutes", "10").param("basePrice", "1.250,50").param("indoor", "true")
			.with(user(v.manager())).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/isletme/sahalar/{id}/duzenle", v.pitch().getId()).with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("JPEG veya PNG, en fazla 3 MB")))
			.andExpect(content().string(containsString("Rezervasyona kapat")));
	}

	@Test
	void ownerScreensAndCustomerHistory() throws Exception {
		Venue v = data.venue();
		Long biz = v.business().getId();
		Long b = v.branch().getId();
		String code = staff.create(v.reception(), b, new StaffReservationService.CreateCommand(v.pitch().getId(),
				DAY.atTime(20, 0), 60, Channel.PHONE, null, "=Misafir", "0532 111 22 33", null));

		mvc.perform(get("/isletme/isletmeler/{x}/personel", biz).param("sube", b.toString()).with(user(v.owner())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Görev ver")))
			.andExpect(content().string(containsString("Resepsiyon / kasa")));
		mvc.perform(get("/isletme/isletmeler/{x}/personel", biz).param("sube", b.toString()).with(user(v.manager())))
			.andExpect(status().isForbidden());
		mvc.perform(get("/isletme/isletmeler/{x}/denetim", biz).param("sube", b.toString()).with(user(v.owner())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("RESERVATION_CREATED_BY_STAFF")));
		// Sahibin menüsünde personel/denetim var, yöneticininkinde yok
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).with(user(v.owner())))
			.andExpect(content().string(containsString("Denetim kaydı")));
		mvc.perform(get("/isletme/subeler/{b}/takvim", b).with(user(v.manager())))
			.andExpect(content().string(not(containsString("Denetim kaydı"))))
			.andExpect(content().string(containsString("Şube ve sahalar")));

		mvc.perform(get("/isletme/rezervasyonlar/{c}/musteri-gecmisi", code).with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Misafir (telefonla eşleşti)")));
	}

}
