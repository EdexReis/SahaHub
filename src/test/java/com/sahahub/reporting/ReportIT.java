package com.sahahub.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.CashService;
import com.sahahub.payment.app.PaymentService;
import com.sahahub.payment.domain.Expense;
import com.sahahub.payment.domain.Payment;
import com.sahahub.reporting.app.ReportService;
import com.sahahub.reporting.domain.ReportCalculator.Grouping;
import com.sahahub.reporting.domain.ReportCalculator.Period;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Raporlar gerçek kayıtlarla: tahsilat, alacak, doluluk, oranlar, yetki ve CSV. */
@IntegrationTest
@AutoConfigureMockMvc
class ReportIT {

	static final LocalDate MON = LocalDate.of(2026, 3, 2);
	static final Period WEEK = new Period(MON, MON.plusDays(6));

	@Autowired
	ReportService reports;

	@Autowired
	StaffReservationService staff;

	@Autowired
	PaymentService payments;

	@Autowired
	CashService cash;

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

	String book(Venue v, LocalDate day, int hour, AppUserPrincipal customer) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), day.atTime(hour, 0), 60, Channel.PHONE, customer == null ? null : customer.email(),
				customer == null ? "Misafir" : null, null, null));
	}

	@Test
	void branchReportFromRealRecords() {
		Venue v = data.venue();
		AppUserPrincipal regular = data.customer();
		String r1 = book(v, MON.plusDays(1), 20, null);
		book(v, MON.plusDays(1), 21, regular);
		book(v, MON.plusDays(2), 20, regular);
		String cancelled = book(v, MON.plusDays(3), 20, data.customer());
		staff.cancel(v.reception(), cancelled, "Şube kararı");

		Payment charge = payments.collect(v.reception(), r1, Payment.Method.MANUAL_POS, new BigDecimal("400"),
				UUID.randomUUID().toString(), null);
		payments.refund(v.manager(), r1, charge.getId(), new BigDecimal("100"), UUID.randomUUID().toString(), null);
		cash.addExpense(v.manager(), v.branch().getId(), Expense.Category.SUPPLIES, new BigDecimal("300"), "Top", false);

		ReportService.BranchReport r = reports.branchReport(v.manager(), v.branch().getId(), WEEK, Grouping.DAY);

		assertThat(r.collections()).hasSize(7);
		assertThat(r.collections().getFirst().pos()).isEqualByComparingTo("400");
		assertThat(r.collectionTotal().net()).isEqualByComparingTo("300");
		assertThat(r.receivables().count()).isEqualTo(3);
		assertThat(r.receivables().booked()).isEqualByComparingTo("3000"); // 3 × 1000 ₺, iptal hariç
		assertThat(r.receivables().collected()).isEqualByComparingTo("300");
		assertThat(r.receivables().outstanding()).isEqualByComparingTo("2700");
		assertThat(r.rates().confirmed()).isEqualTo(4);
		assertThat(r.rates().cancelledByStaff()).isEqualTo(1);
		assertThat(r.rates().cancelRate()).isEqualByComparingTo("25.0");
		assertThat(r.repeat().distinct()).isEqualTo(1);
		assertThat(r.repeat().repeat()).isEqualTo(1);
		assertThat(r.repeat().guestBookings()).isEqualTo(1);
		assertThat(r.usageTotal().reserved()).isEqualTo(180);
		assertThat(r.usageTotal().open()).isEqualTo(7 * 16 * 60); // 09:00–01:00 her gün
		assertThat(r.expenses().total()).isEqualByComparingTo("300");
		assertThat(r.cashDifference()).isEqualByComparingTo("0");
	}

	@Test
	void onlyOwnersAndManagersOfThisBranchSeeReports_onlyOwnersCompare() {
		Venue v = data.venue();
		Venue other = data.venue();
		Long b = v.branch().getId();
		assertThatThrownBy(() -> reports.branchReport(v.reception(), b, WEEK, Grouping.DAY))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> reports.branchReport(other.owner(), b, WEEK, Grouping.DAY))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(reports.branchReport(v.owner(), b, WEEK, Grouping.WEEK).collections()).hasSize(1);
		assertThatThrownBy(() -> reports.compareBranches(v.manager(), v.business().getId(), WEEK))
			.isInstanceOf(AccessDeniedException.class);
		data.secondBranch(v);
		assertThat(reports.compareBranches(v.owner(), v.business().getId(), WEEK)).hasSize(2);
	}

	@Test
	void pageAndCsv() throws Exception {
		Venue v = data.venue();
		book(v, MON.plusDays(1), 20, data.customer());
		Long b = v.branch().getId();
		mvc.perform(get("/isletme/subeler/{b}/raporlar", b).param("bas", MON.toString())
			.param("bit", MON.plusDays(6).toString()).with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Rezervasyon bedeli")))
			.andExpect(content().string(containsString("Doluluk nasıl hesaplanıyor?")));
		mvc.perform(get("/isletme/subeler/{b}/raporlar", b).with(user(v.reception()))).andExpect(status().isForbidden());
		mvc.perform(get("/isletme/subeler/{b}/raporlar", b).param("bas", "2026-03-10").param("bit", "2026-03-01")
			.with(user(v.manager()))).andExpect(status().is3xxRedirection());

		mvc.perform(get("/isletme/subeler/{b}/raporlar.csv", b).param("bas", MON.toString())
			.param("bit", MON.plusDays(6).toString()).param("bolum", "sahalar").with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("sahahub-sahalar-2026-03-02_2026-03-08.csv")))
			.andExpect(content().contentTypeCompatibleWith("text/csv"))
			.andExpect(content().string(startsWith("﻿Saha;Açık dakika")))
			.andExpect(content().string(containsString(";6720;0;6720;60;0;0;0,9;0,9;1;1000,00")));
		mvc.perform(get("/isletme/subeler/{b}/raporlar.csv", b).param("bolum", "tahsilat").with(user(v.reception())))
			.andExpect(status().isForbidden());
		mvc.perform(get("/isletme/isletmeler/{x}/sube-karsilastirma", v.business().getId()).param("sube", b.toString())
			.with(user(v.owner()))).andExpect(status().isOk())
			.andExpect(content().string(containsString("Şube karşılaştırması")));
	}

}
