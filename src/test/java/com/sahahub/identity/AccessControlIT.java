package com.sahahub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.PitchBlockService;
import com.sahahub.booking.app.StaffCalendarService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditEventRepository;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/**
 * Senaryo 11: başka işletmenin kaydına erişme girişimi.
 * Senaryo 12: yetkisiz personel işlemi.
 * Kontroller sunucu tarafındadır; menüde gizlemek yeterli sayılmaz, bu yüzden servisler ve
 * HTTP uç noktaları doğrudan çağrılarak denenir.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AccessControlIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired
	StaffCalendarService calendar;

	@Autowired
	StaffReservationService staff;

	@Autowired
	CustomerBookingService customer;

	@Autowired
	PitchBlockService blocks;

	@Autowired
	AuditEventRepository audit;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	MockMvc mvc;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	String book(Venue v) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Misafir", null, null));
	}

	// ---------------------------------------------------------------- işletmeler arası izolasyon

	@Test
	void ownerCannotSeeAnotherBusinessCalendarOrReservation() {
		Venue a = data.venue();
		Venue b = data.venue();
		String codeInA = book(a);

		assertThatThrownBy(() -> calendar.day(b.owner(), a.branch().getId(), DAY))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> staff.view(b.owner(), codeInA)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> staff.cancel(b.owner(), codeInA, "deneme"))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> staff.move(b.owner(), codeInA, a.pitch().getId(), DAY.atTime(22, 0)))
			.isInstanceOf(AccessDeniedException.class);
		// Kendi şubesindeki sahayı kullanarak başka işletmenin şubesine rezervasyon da açamaz
		assertThatThrownBy(() -> staff.create(b.owner(), a.branch().getId(), new StaffReservationService.CreateCommand(
				a.pitch().getId(), DAY.atTime(22, 0), 60, Channel.PHONE, null, "X", null, null)))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffCannotUsePitchOfAnotherBranchThroughOwnBranchUrl() {
		Venue a = data.venue();
		Venue b = data.venue();
		// b'nin resepsiyonu kendi şubesinin numarasını verip a'nın sahasını kullanmaya çalışır
		assertThatThrownBy(() -> staff.create(b.reception(), b.branch().getId(), new StaffReservationService.CreateCommand(
				a.pitch().getId(), DAY.atTime(22, 0), 60, Channel.PHONE, null, "X", null, null)))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void customerCannotSeeOrCancelSomeoneElsesReservation() {
		Venue v = data.venue();
		AppUserPrincipal alice = data.customer();
		AppUserPrincipal mallory = data.customer();
		String code = customer.hold(alice, v.pitch().getId(), DAY.atTime(21, 0).atZone(TestData.IST).toInstant());

		assertThatThrownBy(() -> customer.view(mallory, code)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> customer.cancel(mallory, code)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> customer.confirm(mallory, code)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void httpRequestToAnotherBusinessCalendarIsForbidden() throws Exception {
		Venue a = data.venue();
		Venue b = data.venue();
		mvc.perform(get("/isletme/subeler/{id}/takvim", a.branch().getId()).with(user(b.owner())))
			.andExpect(status().isForbidden());
		mvc.perform(get("/isletme/subeler/{id}/takvim", a.branch().getId()).with(user(a.reception())))
			.andExpect(status().isOk());
	}

	@Test
	void customerAccountCannotOpenStaffPanel() throws Exception {
		Venue a = data.venue();
		mvc.perform(get("/isletme/subeler/{id}/takvim", a.branch().getId()).with(user(data.customer())))
			.andExpect(status().isForbidden());
		mvc.perform(get("/admin/isletmeler").with(user(a.owner()))).andExpect(status().isForbidden());
	}

	@Test
	void stateChangingRequestWithoutCsrfTokenIsRejected() throws Exception {
		Venue a = data.venue();
		String code = book(a);
		mvc.perform(post("/isletme/rezervasyonlar/{c}/iptal", code).param("reason", "x").with(user(a.reception())))
			.andExpect(status().isForbidden());
		mvc.perform(post("/isletme/rezervasyonlar/{c}/iptal", code).param("reason", "x").with(user(a.reception()))
			.with(csrf())).andExpect(status().is3xxRedirection());
	}

	// ---------------------------------------------------------------- rol yetkileri

	@Test
	void receptionCannotCloseAPitch_managerCan() {
		Venue v = data.venue();
		assertThatThrownBy(() -> blocks.create(v.reception(), v.pitch().getId(), DAY.atTime(10, 0),
				DAY.atTime(12, 0), PitchBlock.Reason.MAINTENANCE, null))
			.isInstanceOf(AccessDeniedException.class);
		Long id = blocks.create(v.manager(), v.pitch().getId(), DAY.atTime(10, 0), DAY.atTime(12, 0),
				PitchBlock.Reason.MAINTENANCE, null);
		assertThat(id).isNotNull();
		assertThatThrownBy(() -> blocks.cancel(v.reception(), id)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void branchStaffCannotActInAnotherBranchOfSameBusiness() {
		Venue v = data.venue();
		Long otherBranchId = data.secondBranch(v).getId();
		// Sahibi işletmenin tüm şubelerini görür; şube personeli yalnızca atandığı şubeyi
		assertThat(calendar.day(v.owner(), otherBranchId, DAY).branchId()).isEqualTo(otherBranchId);
		assertThatThrownBy(() -> calendar.day(v.reception(), otherBranchId, DAY))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> calendar.day(v.manager(), otherBranchId, DAY))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void staffActionsAreAudited() {
		Venue v = data.venue();
		String code = book(v);
		staff.cancel(v.reception(), code, "Müşteri aradı");
		List<String> actions = audit.findAll()
			.stream()
			.filter(e -> v.business().getId().equals(e.getBusinessId()))
			.map(e -> e.getAction())
			.toList();
		assertThat(actions).contains("RESERVATION_CREATED_BY_STAFF", "RESERVATION_CANCELLED_BY_STAFF");
	}

}
