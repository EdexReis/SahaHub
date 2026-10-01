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
	private final com.sahahub.community.app.TeamMatchService teamMatches;
	private final com.sahahub.community.app.ListingQueries listingQueries;

	public TeamController(TeamService service, CatalogService catalog,
			@Value("${sahahub.public-base-url:http://localhost:8080}") String baseUrl,
			com.sahahub.community.app.TeamMatchService teamMatches,
			com.sahahub.community.app.ListingQueries listingQueries) {
		this.service = service;
		this.catalog = catalog;
		this.baseUrl = baseUrl;
		this.teamMatches = teamMatches;
		this.listingQueries = listingQueries;
	}

	@GetMapping("/takimlar")
	public String mine(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		model.addAttribute("teams", service.myTeams(me));
		model.addAttribute("myMatches", teamMatches.myUpcoming(me));
		model.addAttribute("answers", com.sahahub.community.app.TeamMatchService.Answer.values());
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
		model.addAttribute("m", teamMatches.forTeam(me, id));
		model.addAttribute("answers", com.sahahub.community.app.TeamMatchService.Answer.values());
		if (t.captain()) {
			model.addAttribute("reservations", listingQueries.reservationOptions(me));
		}
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

	// ------------------------------------------------------------------ takım maçları ve katılım

	@PostMapping("/takimlar/{id}/maclar")
	public String createMatch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam(name = "rezervasyon", required = false) Long reservationId,
			@RequestParam(name = "zaman", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startsAt,
			@RequestParam(name = "yer", required = false) String place,
			@RequestParam(name = "rakip", required = false) String opponent,
			@RequestParam(name = "not", required = false) String note, RedirectAttributes redirect) {
		teamMatches.create(me, id, reservationId, startsAt, place, opponent, note);
		redirect.addFlashAttribute("flashSuccess", "Takım maçı eklendi; oyunculara bildirim gitti.");
		return "redirect:/takimlar/" + id + "#maclar";
	}

	@PostMapping("/takim-maclari/{matchId}/yanit")
	public String answer(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam("cevap") com.sahahub.community.app.TeamMatchService.Answer answer,
			@RequestParam(name = "takim") Long teamId, @RequestParam(name = "geri", defaultValue = "takim") String back,
			RedirectAttributes redirect) {
		teamMatches.answer(me, matchId, answer);
		redirect.addFlashAttribute("flashSuccess", "Yanıtınız kaydedildi: " + answer.label() + ".");
		return "liste".equals(back) ? "redirect:/takimlar" : "redirect:/takimlar/" + teamId + "#mac-" + matchId;
	}

	@PostMapping("/takim-maclari/{matchId}/iptal")
	public String cancelMatch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam(name = "takim") Long teamId, RedirectAttributes redirect) {
		teamMatches.cancel(me, matchId);
		redirect.addFlashAttribute("flashSuccess", "Takım maçı iptal edildi; oyunculara bildirim gitti.");
		return "redirect:/takimlar/" + teamId + "#maclar";
	}

	@PostMapping("/takim-maclari/{matchId}/skor")
	public String score(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam(name = "takim") Long teamId, @RequestParam(name = "biz", required = false) Integer ours,
			@RequestParam(name = "rakip", required = false) Integer theirs, RedirectAttributes redirect) {
		teamMatches.recordScore(me, matchId, ours, theirs);
		redirect.addFlashAttribute("flashSuccess", "Skor kaydedildi.");
		return "redirect:/takimlar/" + teamId + "#gecmis";
	}

	// ------------------------------------------------------------------ açıklama ve logo

	@PostMapping("/takimlar/{id}/aciklama")
	public String describe(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam(name = "aciklama", required = false) String description, RedirectAttributes redirect) {
		service.describe(me, id, description);
		redirect.addFlashAttribute("flashSuccess", "Takım açıklaması kaydedildi.");
		return "redirect:/takimlar/" + id;
	}

	@PostMapping("/takimlar/{id}/logo")
	public String uploadLogo(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam("dosya") org.springframework.web.multipart.MultipartFile file, RedirectAttributes redirect) {
		service.uploadLogo(me, id, file);
		redirect.addFlashAttribute("flashSuccess", "Logo yüklendi.");
		return "redirect:/takimlar/" + id;
	}

	@PostMapping("/takimlar/{id}/logo/sil")
	public String removeLogo(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		service.removeLogo(me, id);
		redirect.addFlashAttribute("flashSuccess", "Logo kaldırıldı.");
		return "redirect:/takimlar/" + id;
	}

	@GetMapping("/takim-logo/{id}")
	public org.springframework.http.ResponseEntity<byte[]> logo(@PathVariable Long id) {
		return service.logo(id)
			.map(b -> org.springframework.http.ResponseEntity.ok()
				.contentType(org.springframework.http.MediaType.IMAGE_JPEG)
				.cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)))
				.body(b))
			.orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
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
