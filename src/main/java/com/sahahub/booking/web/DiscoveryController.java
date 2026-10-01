package com.sahahub.booking.web;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.sahahub.booking.app.AvailabilityService;
import com.sahahub.booking.app.AvailabilityService.DayAvailability;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.pricing.domain.PriceCalculator;
import com.sahahub.shared.domain.TimeRange;
import com.sahahub.shared.web.Htmx;

import jakarta.servlet.http.HttpServletRequest;

/** Müşteri: saha keşfi, saha ayrıntısı ve gün gün uygun saatler. */
@Controller
public class DiscoveryController {

	private final CatalogService catalog;
	private final AvailabilityService availability;

	public DiscoveryController(CatalogService catalog, AvailabilityService availability) {
		this.catalog = catalog;
		this.availability = availability;
	}

	@GetMapping({ "/", "/sahalar" })
	public String list(@RequestParam(name = "sehir", required = false) String city, Model model) {
		model.addAttribute("cities", catalog.cities());
		model.addAttribute("city", city);
		model.addAttribute("pitches", catalog.publicPitchCards(city));
		return "customer/discover";
	}

	@GetMapping("/sahalar/{pitchId}")
	public String detail(@PathVariable Long pitchId,
			@RequestParam(name = "tarih", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			Model model, HttpServletRequest request) {
		PitchContext ctx = catalog.publicPitch(pitchId);
		LocalDate selected = day == null ? availability.today(ctx) : day;
		DayAvailability da = availability.forDay(ctx, selected);
		model.addAttribute("ctx", ctx);
		model.addAttribute("amenities", CatalogService.amenities(ctx.pitch()));
		model.addAttribute("day", da);
		model.addAttribute("days", availability.dayStrip(ctx));
		if (Htmx.isHtmx(request)) {
			return "customer/pitch :: slots";
		}
		return "customer/pitch";
	}

	/**
	 * Giriş yapmamış kullanıcı bir saate tıkladığında buraya gelir (önce giriş sayfasına
	 * yönlendirilir, sonra geri döner). Saat henüz tutulmamıştır; "Saati tut" ile tutulur.
	 */
	@GetMapping("/sahalar/{pitchId}/sec")
	public String choose(@PathVariable Long pitchId, @RequestParam("baslangic") Instant start, Model model) {
		PitchContext ctx = catalog.publicPitch(pitchId);
		TimeRange play = new TimeRange(start, start.plusSeconds(60L * ctx.pitch().getSlotMinutes()));
		model.addAttribute("ctx", ctx);
		model.addAttribute("start", play.start().atZone(ctx.branch().zone()));
		model.addAttribute("end", play.end().atZone(ctx.branch().zone()));
		model.addAttribute("startParam", start.toString());
		model.addAttribute("quote", PriceCalculator.quote(play, ctx.branch().zone(), ctx.pitch().getBaseHourlyPrice(),
				ctx.pitch().getCurrency(), catalog.priceRules(pitchId)));
		return "customer/choose";
	}

}
