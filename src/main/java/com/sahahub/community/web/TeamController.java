package com.sahahub.community.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.CatalogService;
import com.sahahub.community.app.TeamService;
import com.sahahub.identity.security.AppUserPrincipal;

/** Takımlarım, takım sayfası ve davet bağlantısıyla katılım. */
@Controller
public class TeamController {

	private final TeamService service;
	private final CatalogService catalog;
	private final String baseUrl;

	public TeamController(TeamService service, CatalogService catalog,
			@Value("${sahahub.public-base-url:http://localhost:8080}") String baseUrl) {
		this.service = service;
		this.catalog = catalog;
		this.baseUrl = baseUrl;
	}

	@GetMapping("/takimlar")
	public String mine(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		model.addAttribute("teams", service.myTeams(me));
		model.addAttribute("cities", catalog.cities());
		return "community/teams";
	}

	@PostMapping("/takimlar")
	public String create(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam("ad") String name,
			@RequestParam("sehir") String city, RedirectAttributes redirect) {
		Long id = service.create(me, name, city);
		redirect.addFlashAttribute("flashSuccess", "Takım kuruldu. Davet bağlantısını oyuncularınızla paylaşın.");
		return "redirect:/takimlar/" + id;
	}

	@GetMapping("/takimlar/{id}")
	public String detail(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id, Model model) {
		TeamService.TeamDetail t = service.detail(me, id);
		model.addAttribute("t", t);
		model.addAttribute("inviteUrl", t.inviteCode() == null ? null : baseUrl + "/davet/" + t.inviteCode());
		return "community/team";
	}

	@PostMapping("/takimlar/{id}/ayril")
	public String leave(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		boolean disbanded = service.leave(me, id);
		redirect.addFlashAttribute("flashSuccess", disbanded ? "Takım dağıtıldı." : "Takımdan ayrıldınız.");
		return "redirect:/takimlar";
	}

	@PostMapping("/takimlar/{id}/uyeler/{userId}/cikar")
	public String remove(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@PathVariable Long userId, RedirectAttributes redirect) {
		service.removeMember(me, id, userId);
		redirect.addFlashAttribute("flashSuccess", "Oyuncu takımdan çıkarıldı.");
		return "redirect:/takimlar/" + id;
	}

	@PostMapping("/takimlar/{id}/uyeler/{userId}/kaptan")
	public String transfer(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@PathVariable Long userId, RedirectAttributes redirect) {
		service.transferCaptaincy(me, id, userId);
		redirect.addFlashAttribute("flashSuccess", "Kaptanlık devredildi.");
		return "redirect:/takimlar/" + id;
	}

	@PostMapping("/takimlar/{id}/davet-yenile")
	public String regenerate(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		service.regenerateInvite(me, id);
		redirect.addFlashAttribute("flashSuccess", "Yeni davet bağlantısı oluşturuldu; eski bağlantı artık çalışmaz.");
		return "redirect:/takimlar/" + id;
	}

	@PostMapping("/takimlar/{id}/dagit")
	public String disband(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		service.disband(me, id);
		redirect.addFlashAttribute("flashSuccess", "Takım dağıtıldı. Açık ilanları kapatıldı.");
		return "redirect:/takimlar";
	}

	@GetMapping("/davet/{code}")
	public String invite(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code, Model model) {
		model.addAttribute("p", service.preview(me, code));
		return "community/invite";
	}

	@PostMapping("/davet/{code}")
	public String join(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		Long id = service.join(me, code);
		redirect.addFlashAttribute("flashSuccess", "Takıma katıldınız.");
		return "redirect:/takimlar/" + id;
	}

}
