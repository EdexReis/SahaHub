package com.sahahub.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.CashService;
import com.sahahub.payment.app.PaymentService;
import com.sahahub.payment.domain.Payment;
import com.sahahub.pricing.app.PricingAdminService;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Aşama 3 ekranlarının sunucuda hatasız render edildiği ve temel HTTP akışlarının çalıştığı. */
@IntegrationTest
@AutoConfigureMockMvc
class PaymentPagesRenderIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired MockMvc mvc;
	@Autowired TestData data;
	@Autowired MutableClock clock;
	@Autowired CustomerBookingService customer;
	@Autowired StaffReservationService staff;
	@Autowired PaymentService payments;
	@Autowired CashService cash;
	@Autowired PricingAdminService pricingAdmin;
	@Autowired com.sahahub.payment.domain.PaymentRepository paymentRepo;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	@Test
	void customerDepositFlowThroughSimulationPage() throws Exception {
		Venue v = data.venue();
		data.deposit(v, new DepositPolicy(DepositPolicy.Type.PERCENT, new BigDecimal("30")));
		data.extra(v, "Hakem", "400");
		AppUserPrincipal me = data.customer();
		String code = customer.hold(me, v.pitch().getId(), DAY.atTime(21, 0).atZone(TestData.IST).toInstant());

		mvc.perform(get("/rezervasyon/{c}", code).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Kaporayı öde")))
			.andExpect(content().string(containsString("simülasyondur")))
			.andExpect(content().string(not(containsString("Rezervasyonu onayla</button>"))));

		String redirect = mvc.perform(post("/rezervasyon/{c}/odeme", code).param("secenek", "DEPOSIT")
			.param("anahtar", "k-" + code).with(user(me)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrlPattern("/odeme-saglayici/simulasyon/*"))
			.andReturn().getResponse().getRedirectedUrl();

		mvc.perform(get(redirect).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("DEMO ÖDEME SAĞLAYICISI")));
		// Başkası bu ödeme sayfasını göremez
		mvc.perform(get(redirect).with(user(data.customer()))).andExpect(status().isNotFound());

		mvc.perform(post(redirect).param("scenario", "SUCCESS").with(user(me)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		mvc.perform(get("/rezervasyon/{c}", code).with(user(me)))
			.andExpect(content().string(containsString("Onaylandı")))
			.andExpect(content().string(containsString("Çevrim içi (simülasyon)")));
		mvc.perform(get("/rezervasyon/{c}/ozet", code).with(user(me)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("fatura veya mali belge değildir")));
	}

	@Test
	void staffPanelCashAndPricingPages() throws Exception {
		Venue v = data.venue();
		data.extra(v, "Krampon", "60");
		data.coupon(v, "TEST10", Coupon.Kind.PERCENT, "10", 5);
		pricingAdmin.addRule(v.owner(), v.branch().getId(), new PricingAdminService.RuleCommand(v.pitch().getId(),
				"Bayram", java.util.EnumSet.of(java.time.DayOfWeek.SATURDAY), java.time.LocalTime.of(18, 0), null,
				new BigDecimal("1500"), 20, DAY, DAY.plusDays(3)));
		String code = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Misafir", null, null));
		cash.open(v.reception(), v.branch().getId(), new BigDecimal("100"));
		payments.collect(v.reception(), code, Payment.Method.CASH, new BigDecimal("300"), "k1", null);

		mvc.perform(get("/isletme/subeler/{b}/takvim", v.branch().getId()).param("tarih", DAY.toString())
			.param("secili", code).with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Tahsilat al")))
			.andExpect(content().string(containsString("Personel indirimi")))
			.andExpect(content().string(containsString("Kısmi ödendi")));
		// Resepsiyon: iade ve indirim düğmesi görmez
		mvc.perform(get("/isletme/rezervasyonlar/{c}", code).header("HX-Request", "true").with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("İadeyi kaydet"))))
			.andExpect(content().string(not(containsString("Personel indirimi"))));

		mvc.perform(get("/isletme/subeler/{b}/kasa", v.branch().getId()).with(user(v.reception())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Kasada olması gereken")));
		mvc.perform(get("/isletme/subeler/{b}/fiyatlar", v.branch().getId()).with(user(v.owner())))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("TEST10")))
			.andExpect(content().string(containsString("Bayram")));
		// Şube yöneticisi fiyatları görür ama kupon bölümünü görmez; resepsiyon fiyat ekranına giremez
		mvc.perform(get("/isletme/subeler/{b}/fiyatlar", v.branch().getId()).with(user(v.manager())))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("TEST10"))));
		mvc.perform(get("/isletme/subeler/{b}/fiyatlar", v.branch().getId()).with(user(v.reception())))
			.andExpect(status().isForbidden());
		mvc.perform(get("/isletme/rezervasyonlar/{c}/ozet", code).with(user(v.reception()))).andExpect(status().isOk());

		mvc.perform(post("/isletme/rezervasyonlar/{c}/tahsilat", code).param("method", "MANUAL_POS")
			.param("amount", "200,50").param("anahtar", "k2").with(user(v.reception())).with(csrf()))
			.andExpect(status().is3xxRedirection());
		org.assertj.core.api.Assertions.assertThat(paymentRepo.findByIdempotencyKey("k2").orElseThrow().getAmount())
			.isEqualByComparingTo("200.50");
		// Geçersiz tutar teknik hata sayfası değil, mesajla geldiği sayfaya dönüş
		mvc.perform(post("/isletme/rezervasyonlar/{c}/tahsilat", code).param("method", "MANUAL_POS")
			.param("amount", "abc").param("anahtar", "k3").header("Referer", "/isletme/takvim")
			.with(user(v.reception())).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
				.attribute("flashError", "Geçerli bir tutar girin (ör. 1.250,50)."));
	}

	@Test
	void webhookEndpointRequiresValidSignatureButNoCsrf() throws Exception {
		mvc.perform(post("/webhooks/odeme/simulasyon").contentType(MediaType.APPLICATION_JSON)
			.content("{\"eventId\":\"e1\",\"providerRef\":\"x\",\"outcome\":\"SUCCEEDED\"}")
			.header("X-Sim-Signature", "yanlis"))
			.andExpect(status().isUnauthorized());
	}

}
