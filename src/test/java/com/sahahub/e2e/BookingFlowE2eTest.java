package com.sahahub.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** Müşterinin rezervasyon oluşturması ve personelin hızlı rezervasyonu, gerçek tarayıcıda. */
class BookingFlowE2eTest extends E2eTestBase {

	@Test
	void customerPicksSlotAndConfirmsReservation() {
		login(page, "musteri@sahahub.test");
		page.navigate("/sahalar");
		page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Saha 2 · Açık")).click();
		chooseDay(page, 2);
		Locator firstFree = page.locator("button.slot").first();
		assertThat(firstFree).isVisible();
		String label = firstFree.getAttribute("aria-label");
		firstFree.click();

		page.waitForURL(Pattern.compile(".*/rezervasyon/[A-Z0-9]{8}$"));
		assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Rezervasyonu tamamla")))
			.isVisible();
		assertThat(page.locator(".hold-timer")).containsText("dakika daha");
		assertThat(page.locator(".price-table tfoot")).containsText("₺");
		assertThat(page.locator(".paybadge")).hasText("Kapora bekleniyor");

		// Kadıköy şubesi %30 kapora istiyor: onay, simülasyon sağlayıcısında ödemeyle gelir
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(Pattern.compile("^Kaporayı öde"))).click();
		page.waitForURL(Pattern.compile(".*/odeme-saglayici/simulasyon/.*"));
		assertThat(page.locator(".sim-banner")).containsText("DEMO");
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Ödeme başarılı")).click();
		page.waitForURL(Pattern.compile(".*/rezervasyon/[A-Z0-9]{8}\\?odeme=success$"));
		assertThat(page.locator(".status")).hasText("Onaylandı");
		assertThat(page.locator(".paybadge")).hasText("Kısmi ödendi");
		assertThat(page.locator(".pay-history")).containsText("Çevrim içi (simülasyon)");

		page.navigate("/rezervasyonlarim");
		assertThat(page.locator("#upcoming-title + .res-list")).containsText("Saha 2");
		org.assertj.core.api.Assertions.assertThat(label).contains("saati tut");
	}

	@Test
	void takenSlotShowsClearErrorInsteadOfDoubleBooking() {
		login(page, "musteri@sahahub.test");
		page.navigate("/sahalar");
		page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Saha 1 · Kapalı")).click();
		chooseDay(page, 3);
		Locator slot = page.locator("button.slot").first();
		String time = slot.locator(".s-time").textContent();

		// Aynı anda başka bir müşteri aynı saati alıyor
		var other = newContext(1366, 900);
		Page otherPage = other.newPage();
		login(otherPage, "kaptan@sahahub.test");
		otherPage.navigate(page.url());
		otherPage.locator("button.slot").filter(new Locator.FilterOptions().setHasText(time)).first().click();
		otherPage.waitForURL(Pattern.compile(".*/rezervasyon/[A-Z0-9]{8}$"));
		other.close();

		slot.click(); // ilk müşteri bayat ekrandan tıklıyor
		assertThat(page.locator(".alert-error")).containsText("Bu saat artık uygun değil");
		shot(page, "desktop-07-slot-taken-error");
	}

	@Test
	void receptionCreatesWalkInBookingFromFreeSlot() {
		login(page, "resepsiyon.kadikoy@yesilvadi.test");
		page.navigate("/isletme");
		assertThat(page.locator(".cal")).isVisible();

		Locator free = page.locator("a.ev-free").last();
		String freeLabel = free.getAttribute("aria-label");
		openPanel(page, free);
		assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Hızlı rezervasyon")))
			.isVisible();
		page.getByLabel("Müşteri adı").fill("Yürüyen Müşteri E2E");
		page.getByLabel("Telefon").fill("0555 123 45 67");
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Rezervasyonu oluştur")).click();

		assertThat(page.locator(".alert-success")).containsText("Rezervasyon oluşturuldu");
		assertThat(page.locator("#panel h2")).hasText("Yürüyen Müşteri E2E");
		assertThat(page.locator("a.ev").filter(new Locator.FilterOptions().setHasText("Yürüyen Müşteri E2E")))
			.hasCount(1);
		org.assertj.core.api.Assertions.assertThat(freeLabel).contains("boş");
	}

	/** Kabul kriteri: personelin tahsilat kaydetmesi (nakit, açık kasaya). */
	@Test
	void receptionCollectsDepositInCash() {
		login(page, "resepsiyon.kadikoy@yesilvadi.test");
		page.navigate("/isletme");
		Locator dueBooking = page.locator("a.ev").filter(new Locator.FilterOptions().setHasText("Okan Tunç"));
		openPanel(page, dueBooking);
		assertThat(page.locator("#panel .paybadge").first()).hasText("Kapora bekleniyor");

		page.locator("#panel").getByLabel("Nakit").check();
		page.locator("#panel").getByLabel("Not").first().fill("Kapora nakit");
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Tahsilatı kaydet")).click();

		assertThat(page.locator(".alert-success")).containsText("Tahsilat kaydedildi");
		assertThat(page.locator("#panel .paybadge").first()).hasText("Kısmi ödendi");
		assertThat(page.locator("#panel .pay-history")).containsText("Kapora nakit");

		page.navigate(page.url().replaceAll("/takvim.*$", "/kasa"));
		assertThat(page.locator("main")).containsText("Kasada olması gereken");
		shot(page, "desktop-17-cash-after-collection");
	}

	@Test
	void quickBookingShowsFieldErrorsNextToFields() {
		login(page, "resepsiyon.kadikoy@yesilvadi.test");
		page.navigate("/isletme");
		openPanel(page, page.locator("a.ev-free").last());
		page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Rezervasyonu oluştur")).click();
		assertThat(page.locator("#panel .error-text")).containsText("Müşteri adını yazın");
	}

}
