package com.sahahub.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/**
 * Kritik ekranları telefon (390×844) ve masaüstü (1366×900) boyutlarında açar, yatay taşma
 * olmadığını doğrular ve target/screenshots altına ekran görüntüsü kaydeder. Görüntüler tasarım
 * incelemesinde kullanılır.
 */
class ScreenTourE2eTest extends E2eTestBase {

	record Size(String name, int width, int height) {
	}

	static final List<Size> SIZES = List.of(new Size("mobile", 390, 844), new Size("desktop", 1366, 900));

	@Test
	void customerScreens() {
		for (Size size : SIZES) {
			BrowserContext ctx = newContext(size.width(), size.height());
			Page p = ctx.newPage();
			p.navigate("/sahalar");
			check(p, size, "01-discover");

			p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Saha 2 · Açık")).click();
			check(p, size, "02-pitch-anonymous");

			login(p, "uzun.isim@sahahub.test");
			p.navigate("/sahalar");
			p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Saha 1 · Kapalı")).click();
			chooseDay(p, 1);
			check(p, size, "03-pitch-day");

			p.locator("button.slot").last().click();
			p.waitForURL(Pattern.compile(".*/rezervasyon/[A-Z0-9]{8}$"));
			check(p, size, "04-reservation-held");
			p.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile("^Kaporayı öde"))).click();
			p.waitForURL(Pattern.compile(".*/odeme-saglayici/simulasyon/.*"));
			check(p, size, "19-payment-simulation");
			p.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Ödeme başarılı")).click();
			p.waitForURL(Pattern.compile(".*odeme=success$"));
			check(p, size, "20-reservation-paid");
			p.navigate(p.url().replaceAll("\\?.*$", "") + "/ozet");
			check(p, size, "21-receipt");

			p.navigate("/rezervasyonlarim");
			check(p, size, "05-my-reservations");

			// Kapalı gün (Ataşehir şubesinde kurgusal tatil)
			p.navigate("/sahalar");
			p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Çatı Sahası")).click();
			p.locator(".day.is-closed").first().click();
			assertThat(p.locator(".empty")).containsText("kapalı");
			check(p, size, "06-closed-day");
			ctx.close();
		}
	}

	@Test
	void emptyStatesAndForms() {
		for (Size size : SIZES) {
			BrowserContext ctx = newContext(size.width(), size.height());
			Page p = ctx.newPage();
			p.navigate("/kayit");
			p.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hesap oluştur")).click();
			assertThat(p.locator(".error-text").first()).isVisible();
			check(p, size, "08-register-errors");

			p.getByLabel("Ad soyad").fill("Yeni Kullanıcı");
			p.getByLabel("E-posta").fill("yeni-" + size.name() + "@sahahub.test");
			p.getByLabel("Parola").fill("uzun-bir-parola-1");
			p.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hesap oluştur")).click();
			p.navigate("/rezervasyonlarim");
			assertThat(p.locator(".empty")).containsText("Henüz rezervasyonunuz yok");
			check(p, size, "09-my-reservations-empty");
			ctx.close();
		}
	}

	@Test
	void staffScreens() {
		for (Size size : SIZES) {
			BrowserContext ctx = newContext(size.width(), size.height());
			Page p = ctx.newPage();
			login(p, "resepsiyon.kadikoy@yesilvadi.test");
			p.navigate("/isletme");
			assertThat(p.locator(".cal")).isVisible();
			check(p, size, "10-staff-calendar");

			openPanel(p, p.locator("a.ev:not(.ev-free)").first());
			assertThat(p.locator("#panel .eyebrow")).containsText("Rezervasyon");
			check(p, size, "11-staff-reservation-panel");

			p.navigate("/isletme");
			openPanel(p, p.locator("a.ev-free").first());
			assertThat(p.locator("#panel h2")).hasText("Hızlı rezervasyon");
			check(p, size, "12-staff-quick-booking");

			p.navigate(p.url().replaceAll("\\?.*$", "") + "?gorunum=hafta");
			assertThat(p.locator(".cal-head").nth(1)).isVisible();
			check(p, size, "13-staff-week");
			ctx.close();
		}
		BrowserContext ctx = newContext(1366, 900);
		Page p = ctx.newPage();
		login(p, "sahip@yesilvadi.test");
		p.navigate("/isletme");
		p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Kadıköy Şubesi")).click(); // sahip iki şubeyi görür
		openPanel(p, p.locator("a.ev").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Kaan Er")));
		assertThat(p.locator("#panel h3").first()).hasText("Ödeme");
		check(p, new Size("desktop", 1366, 900), "15-staff-payment-panel");
		p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Fiyatlandırma")).click();
		assertThat(p.locator("h1")).hasText("Fiyatlandırma");
		check(p, new Size("desktop", 1366, 900), "16-staff-pricing");
		ctx.close();

		for (Size size : SIZES) {
			BrowserContext c2 = newContext(size.width(), size.height());
			Page q = c2.newPage();
			login(q, "resepsiyon.kadikoy@yesilvadi.test");
			q.navigate("/isletme");
			q.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Kasa")).click();
			assertThat(q.locator("h1")).hasText("Kasa");
			check(q, size, "18-cash");
			c2.close();
		}

		ctx = newContext(1366, 900);
		p = ctx.newPage();
		login(p, "mudur.kadikoy@yesilvadi.test");
		p.navigate("/isletme");
		openPanel(p, p.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Saha kapat")));
		assertThat(p.locator("#panel h2")).hasText("Saha kapat");
		check(p, new Size("desktop", 1366, 900), "14-staff-block-form");
		ctx.close();
	}

	/** Sayfada yatay taşma olmamalı (telefonda yanlara kayan sayfa kullanılabilirliği bozar). */
	private static void check(Page p, Size size, String name) {
		shot(p, size.name() + "-" + name);
		Object overflow = p.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth");
		org.assertj.core.api.Assertions.assertThat(((Number) overflow).intValue())
			.as("%s/%s yatay taşma", size.name(), name)
			.isLessThanOrEqualTo(0);
	}

}
