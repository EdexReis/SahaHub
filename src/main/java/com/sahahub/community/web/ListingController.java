package com.sahahub.community.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.CatalogService;
import com.sahahub.community.app.ListingQueries;
import com.sahahub.community.app.ListingService;
import com.sahahub.community.app.TeamService;
import com.sahahub.community.domain.Listing;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;

/** Oyuncu/rakip ilanları: liste ve ayrıntı herkese açık; ilan açmak ve başvurmak giriş ister. */
@Controller
public class ListingController {

	private final ListingService service;
	private final ListingQueries queries;
	private final TeamService teams;
	private final CatalogService catalog;

	public ListingController(ListingService service, ListingQueries queries, TeamService teams,
			CatalogService catalog) {
		this.service = service;
		this.queries = queries;
		this.teams = teams;
		this.catalog = catalog;
	}

	@GetMapping("/ilanlar")
	public String list(@RequestParam(name = "sehir", required = false) String city,
			@RequestParam(name = "tur", required = false) Listing.Kind kind, Model model) {
		model.addAttribute("cards", queries.open(city, kind));
		model.addAttribute("cities", queries.cities());
		model.addAttribute("city", city);
		model.addAttribute("kind", kind);
		return "community/listings";
	}

	@GetMapping("/ilanlar/{id:[0-9]+}")
	public String detail(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id, Model model) {
		model.addAttribute("d", queries.detail(me, id));
		return "community/listing";
	}

	@GetMapping("/ilanlar/yeni")
	public String form(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		return renderForm(me, new ListingForm(), model);
	}

	@PostMapping("/ilanlar")
	public String create(@AuthenticationPrincipal AppUserPrincipal me, @ModelAttribute("form") ListingForm form,
			Model model, RedirectAttributes redirect) {
		try {
			Long id = service.create(me, form.toCommand());
			redirect.addFlashAttribute("flashSuccess", "İlan yayında.");
			return "redirect:/ilanlar/" + id;
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("formError", ex.getMessage());
			return renderForm(me, form, model);
		}
	}

	@PostMapping("/ilanlar/{id}/basvur")
	public String apply(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam(name = "mesaj", required = false) String message,
			@RequestParam(name = "takim", required = false) Long teamId, RedirectAttributes redirect) {
		service.apply(me, id, message, teamId);
		redirect.addFlashAttribute("flashSuccess", "Başvurunuz iletildi. Yanıt gelince bildirim alırsınız.");
		return "redirect:/ilanlar/" + id;
	}

	@PostMapping("/ilanlar/{id}/kapat")
	public String close(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		service.close(me, id);
		redirect.addFlashAttribute("flashSuccess", "İlan kapatıldı.");
		return "redirect:/ilanlar/" + id;
	}

	@PostMapping("/basvurular/{id}/kabul")
	public String accept(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam Long ilan, RedirectAttributes redirect) {
		service.accept(me, id);
		redirect.addFlashAttribute("flashSuccess", "Başvuru kabul edildi.");
		return "redirect:/ilanlar/" + ilan;
	}

	@PostMapping("/basvurular/{id}/ret")
	public String reject(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam Long ilan, RedirectAttributes redirect) {
		service.reject(me, id);
		redirect.addFlashAttribute("flashSuccess", "Başvuru reddedildi.");
		return "redirect:/ilanlar/" + ilan;
	}

	@PostMapping("/basvurular/{id}/geri-cek")
	public String withdraw(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam Long ilan, RedirectAttributes redirect) {
		service.withdraw(me, id);
		redirect.addFlashAttribute("flashSuccess", "Başvurunuz geri çekildi.");
		return "redirect:/ilanlar/" + ilan;
	}

	@GetMapping("/ilanlarim")
	public String mine(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		model.addAttribute("cards", queries.mine(me));
		model.addAttribute("applications", queries.myApplications(me));
		return "community/my-listings";
	}

	private String renderForm(AppUserPrincipal me, ListingForm form, Model model) {
		model.addAttribute("form", form);
		model.addAttribute("teams", teams.captainOf(me));
		model.addAttribute("reservations", queries.reservationOptions(me));
		model.addAttribute("cities", catalog.cities());
		return "community/listing-form";
	}

}
