package com.sahahub.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.AvailabilityService;
import com.sahahub.booking.app.CustomerHistoryService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.business.app.BranchAdminService;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.PitchPhotoService;
import com.sahahub.business.app.StaffAdminService;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.business.domain.Surface;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Saha/şube ayarları, fotoğraf, personel, denetim kaydı ve müşteri geçmişi. */
@IntegrationTest
@AutoConfigureMockMvc
class AdminIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	BranchAdminService admin;

	@Autowired
	PitchPhotoService photos;

	@Autowired
	StaffAdminService staffAdmin;

	@Autowired
	CustomerHistoryService history;

	@Autowired
	StaffReservationService staff;

	@Autowired
	AvailabilityService availability;

	@Autowired
	CatalogService catalog;

	@Autowired
	PitchRepository pitches;

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

	static BranchAdminService.PitchCommand pitch(String name, int slot, int step, int buffer) {
		return new BranchAdminService.PitchCommand(name, null, 14, 50, 30, false, Surface.ARTIFICIAL_TURF, true, false,
				true, true, slot, step, buffer);
	}

	String book(Venue v, LocalDate day, int hour, String guest, String phone) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), day.atTime(hour, 0), 60, Channel.PHONE, null, guest, phone, null));
	}

	static byte[] png() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	@Test
	void pitchCreateUpdateAndDeactivationRule() {
		Venue v = data.venue();
		Long id = admin.createPitch(v.manager(), v.branch().getId(), pitch("Yeni Saha", 90, 90, 15),
				new BigDecimal("1500"));
		assertThat(pitches.findById(id).orElseThrow().getBufferMinutes()).isEqualTo(15);
		assertThatThrownBy(() -> admin.createPitch(v.manager(), v.branch().getId(), pitch("X", 50, 60, 0),
				BigDecimal.TEN)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("15'in katı");
		assertThatThrownBy(() -> admin.createPitch(v.reception(), v.branch().getId(), pitch("X", 60, 60, 0),
				BigDecimal.TEN)).isInstanceOf(AccessDeniedException.class);

		String code = book(v, DAY, 20, "Misafir", null);
		Long existing = v.pitch().getId();
		admin.updatePitch(v.manager(), existing, pitch("Saha Yeni Ad", 90, 90, 30));
		// Mevcut rezervasyonun süresi ve hazırlığı değişmez
		assertThat(jdbc.queryForObject("select buffer_minutes from reservation where code = ?", Integer.class, code))
			.isZero();
		assertThatThrownBy(() -> admin.setPitchActive(v.manager(), existing, false))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("1 ileri tarihli");
		staff.cancel(v.reception(), code, "Test");
		admin.setPitchActive(v.manager(), existing, false);
		assertThatThrownBy(() -> catalog.publicPitch(existing)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void hoursSpecialDaysAndPolicies() {
		Venue v = data.venue();
		Long b = v.branch().getId();
		book(v, DAY, 22, "Geç Maç", null);
		Map<DayOfWeek, DayHours> week = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek d : DayOfWeek.values()) {
			week.put(d, DayHours.open(LocalTime.of(10, 0), LocalTime.of(22, 0)));
		}
		// 22:00 maçı yeni saatlerin dışında kalır: silinmez, sayısı bildirilir
		assertThat(admin.updateWeek(v.manager(), b, week)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from reservation where branch_id = ? and status = 'CONFIRMED'",
				Integer.class, b)).isEqualTo(1);

		admin.addSpecialDay(v.manager(), b, DAY.plusDays(1), DayHours.CLOSED, "Bayram");
		assertThatThrownBy(() -> admin.addSpecialDay(v.manager(), b, DAY.plusDays(1), DayHours.CLOSED, null))
			.isInstanceOf(BusinessRuleException.class);
		assertThat(availability.forDay(catalog.publicPitch(v.pitch().getId()), DAY.plusDays(1)).status().name())
			.isEqualTo("CLOSED");
		assertThatThrownBy(() -> admin.addSpecialDay(v.manager(), b, LocalDate.of(2026, 3, 1), DayHours.CLOSED, null))
			.isInstanceOf(BusinessRuleException.class);

		assertThatThrownBy(() -> admin.updatePolicies(v.manager(), b, 2, 24, 30)).isInstanceOf(BusinessRuleException.class);
		admin.updatePolicies(v.manager(), b, 15, 12, 45);
		assertThat(admin.settings(v.manager(), b).holdMinutes()).isEqualTo(15);
		assertThatThrownBy(() -> admin.settings(v.reception(), b)).isInstanceOf(AccessDeniedException.class);
		assertThat(jdbc.queryForObject("select count(*) from audit_event where business_id = ? and action like 'BRANCH_%'",
				Integer.class, v.business().getId())).isEqualTo(3);
	}

	@Test
	void photoUploadAccessAndReplacement() throws Exception {
		Venue v = data.venue();
		Long p = v.pitch().getId();
		mvc.perform(multipart("/isletme/sahalar/{id}/fotograf", p)
			.file(new MockMultipartFile("dosya", "saha.png", "image/png", png()))
			.with(user(v.reception())).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(multipart("/isletme/sahalar/{id}/fotograf", p)
			.file(new MockMultipartFile("dosya", "kotu.png", "image/png", "<svg/>".getBytes()))
			.with(user(v.manager())).with(csrf()))
			.andExpect(flash().attribute("flashError", "Yalnızca JPEG veya PNG fotoğraf yüklenebilir."));
		mvc.perform(multipart("/isletme/sahalar/{id}/fotograf", p)
			.file(new MockMultipartFile("dosya", "saha.png", "image/png", png()))
			.with(user(v.manager())).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", "Fotoğraf yüklendi."));
		String first = pitches.findById(p).orElseThrow().getPhotoPath();
		assertThat(first).matches("[0-9a-f\\-]{36}\\.jpg");

		// Herkese açık (saha rezervasyona açık)
		mvc.perform(get("/saha-fotograf/{id}", p)).andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"));
		mvc.perform(get("/sahalar")).andExpect(content().string(org.hamcrest.Matchers.containsString("/saha-fotograf/" + p)));

		// Yenisi yüklenince eski dosya silinir
		photos.upload(v.manager(), p, new MockMultipartFile("dosya", "b.png", "image/png", png()));
		String dir = System.getProperty("java.io.tmpdir") + "/sahahub-test-uploads/";
		assertThat(new java.io.File(dir + first)).doesNotExist();
		assertThat(new java.io.File(dir + pitches.findById(p).orElseThrow().getPhotoPath())).exists();

		// Rezervasyona kapalı sahanın fotoğrafı yalnızca yöneticiye
		admin.setPitchActive(v.manager(), p, false);
		mvc.perform(get("/saha-fotograf/{id}", p)).andExpect(status().isNotFound());
		mvc.perform(get("/saha-fotograf/{id}", p).with(user(data.customer()))).andExpect(status().isNotFound());
		mvc.perform(get("/saha-fotograf/{id}", p).with(user(v.manager()))).andExpect(status().isOk());
		mvc.perform(get("/saha-fotograf/{id}", 999999)).andExpect(status().isNotFound());
	}

	@Test
	void staffAssignmentAndRevocation() {
		Venue v = data.venue();
		Long biz = v.business().getId();
		AppUserPrincipal newcomer = data.customer();
		assertThatThrownBy(() -> staffAdmin.assign(v.manager(), biz, newcomer.email(), StaffRole.RECEPTION,
				v.branch().getId())).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> staffAdmin.assign(v.owner(), biz, "yok@test.local", StaffRole.RECEPTION,
				v.branch().getId())).isInstanceOf(BusinessRuleException.class).hasMessageContaining("kayıt olmalı");
		Venue other = data.venue();
		assertThatThrownBy(() -> staffAdmin.assign(v.owner(), biz, newcomer.email(), StaffRole.RECEPTION,
				other.branch().getId())).isInstanceOf(BusinessRuleException.class).as("başka işletmenin şubesi");

		staffAdmin.assign(v.owner(), biz, newcomer.email().toUpperCase(java.util.Locale.ROOT), StaffRole.RECEPTION,
				v.branch().getId());
		AppUserPrincipal asStaff = new AppUserPrincipal(newcomer.id(), newcomer.email(), "Yeni", "x", true, false, true);
		assertThat(staff.view(asStaff, book(v, DAY, 20, "A", null))).isNotNull();

		Long membership = staffAdmin.staff(v.owner(), biz).stream()
			.filter(r -> r.userId().equals(newcomer.id()))
			.findFirst().orElseThrow().membershipId();
		staffAdmin.revoke(v.owner(), biz, membership);
		// Yetki hemen kalkar (oturum yenilenmeden)
		assertThatThrownBy(() -> staff.view(asStaff, book(v, DAY, 21, "B", null)))
			.isInstanceOf(AccessDeniedException.class);
		// Aynı kapsama yeniden atama eski satırı canlandırır, yeni yönetici rolüyle
		staffAdmin.assign(v.owner(), biz, newcomer.email(), StaffRole.BRANCH_MANAGER, v.branch().getId());
		assertThat(jdbc.queryForObject("select count(*) from staff_membership where user_id = ?", Integer.class,
				newcomer.id())).isEqualTo(1);

		Long ownerMembership = staffAdmin.staff(v.owner(), biz).stream()
			.filter(r -> r.role() == StaffRole.OWNER).findFirst().orElseThrow().membershipId();
		assertThatThrownBy(() -> staffAdmin.revoke(v.owner(), biz, ownerMembership))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("Kendi görevinizi");
		staffAdmin.assign(v.owner(), biz, newcomer.email(), StaffRole.OWNER, null);
		AppUserPrincipal secondOwner = new AppUserPrincipal(newcomer.id(), newcomer.email(), "Yeni", "x", true, false,
				true);
		staffAdmin.revoke(secondOwner, biz, ownerMembership); // iki sahip varken biri kaldırılabilir
		Long last = staffAdmin.staff(secondOwner, biz).stream()
			.filter(r -> r.role() == StaffRole.OWNER && r.active()).findFirst().orElseThrow().membershipId();
		assertThatThrownBy(() -> staffAdmin.revoke(secondOwner, biz, last)).isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void auditLogIsOwnerOnlyAndScopedToBusiness() {
		Venue v = data.venue();
		Venue other = data.venue();
		String code = book(v, DAY, 20, "A", null);
		staff.cancel(v.reception(), code, "Test iptali");
		book(other, DAY, 20, "B", null);
		StaffAdminService.AuditPage page = staffAdmin.audit(v.owner(), v.business().getId(), null, null, null, 0);
		assertThat(page.rows()).isNotEmpty();
		assertThat(page.actions()).contains("RESERVATION_CANCELLED_BY_STAFF");
		assertThat(staffAdmin.audit(v.owner(), v.business().getId(), "RESERVATION_CANCELLED_BY_STAFF", DAY.minusDays(5),
				DAY.plusDays(5), 0).rows()).singleElement().satisfies(r -> assertThat(r.details()).contains("Test iptali"));
		assertThatThrownBy(() -> staffAdmin.audit(v.manager(), v.business().getId(), null, null, null, 0))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> staffAdmin.audit(other.owner(), v.business().getId(), null, null, null, 0))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void customerHistoryStaysInsideTheBranch() {
		Venue v = data.venue();
		var branch2 = data.secondBranch(v);
		String a = book(v, DAY, 20, "Ali", "0532 111 22 33");
		book(v, DAY.plusDays(1), 20, "Ali V.", "+90 (532) 111-22-33".replace("+90 ", "0")); // aynı rakamlar
		book(v, DAY.plusDays(2), 20, "Başkası", "0532 999 88 77");
		CustomerHistoryService.History h = history.forReservation(v.reception(), a);
		assertThat(h.registered()).isFalse();
		assertThat(h.total()).isEqualTo(2);

		AppUserPrincipal c = data.customer();
		String r1 = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(22, 0), 60, Channel.PHONE, c.email(), null, null, null));
		Long pitch2 = jdbc.queryForObject("""
				insert into pitch (branch_id, name, capacity_players, surface, base_hourly_price, created_at)
				values (?, 'Diğer Şube Sahası', 14, 'ARTIFICIAL_TURF', 1000, now()) returning id""", Long.class,
				branch2.getId());
		jdbc.update("""
				insert into reservation (code, business_id, branch_id, pitch_id, customer_id, starts_at, ends_at,
				  buffer_minutes, status, channel, total_amount, currency, confirmed_at)
				values ('DIGERSUBE1', ?, ?, ?, ?, now() + interval '3 day', now() + interval '3 day 1 hour', 0,
				  'CONFIRMED', 'PHONE', 1000, 'TRY', now())""", v.business().getId(), branch2.getId(), pitch2, c.id());
		CustomerHistoryService.History reg = history.forReservation(v.reception(), r1);
		assertThat(reg.registered()).isTrue();
		assertThat(reg.rows()).extracting(CustomerHistoryService.Row::code).containsExactly(r1);
		Venue other = data.venue();
		assertThatThrownBy(() -> history.forReservation(other.owner(), r1)).isInstanceOf(AccessDeniedException.class);
	}

}
