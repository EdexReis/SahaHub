package com.sahahub.tournament.web;

import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.StaffBranchService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.tournament.app.TournamentQueries;
import com.sahahub.tournament.app.TournamentService;

/**
 * Lig ekranları. Personel tarafı /isletme altında (TOURNAMENT_MANAGE); fikstür ve puan durumu /ligler
 * altında herkese açık (taslak ligler hariç).
 */
@Controller
public class TournamentController {

	private final TournamentService service;
	private final TournamentQueries queries;
	private final StaffBranchService branches;

	public TournamentController(TournamentService service, TournamentQueries queries, StaffBranchService branches) {
		this.service = service;
		this.queries = queries;
		this.branches = branches;
	}

	// ------------------------------------------------------------------ herkese açık

	@GetMapping("/ligler")
	public String publicList(Model model) {
		model.addAttribute("rows", queries.publicList());
		return "tournament/public-list";
	}

	@GetMapping("/ligler/{id:[0-9]+}")
	public String publicDetail(@PathVariable Long id, Model model) {
		model.addAttribute("d", queries.forPublic(id));
		return "tournament/public-detail";
	}

	// ------------------------------------------------------------------ personel

	@GetMapping("/isletme/subeler/{branchId}/ligler")
	public String staffList(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		model.addAttribute("rows", queries.forBranch(me, branchId));
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		return "tournament/staff-list";
	}

	@PostMapping("/isletme/subeler/{branchId}/ligler")
	public String create(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam("ad") String name, @RequestParam(name = "ciftDevre", defaultValue = "false") boolean doubleRound,
			@RequestParam(name = "galibiyet", defaultValue = "3") int win,
			@RequestParam(name = "beraberlik", defaultValue = "1") int draw,
			@RequestParam(name = "maglubiyet", defaultValue = "0") int loss,
			@RequestParam(name = "bicim", defaultValue = "LEAGUE") com.sahahub.tournament.domain.Tournament.Format format,
			@RequestParam(name = "ucunculuk", defaultValue = "false") boolean thirdPlace, RedirectAttributes redirect) {
		Long id = service.create(me, branchId, name, format, doubleRound, win, draw, loss, thirdPlace);
		redirect.addFlashAttribute("flashSuccess", format == com.sahahub.tournament.domain.Tournament.Format.KNOCKOUT
				? "Turnuva oluşturuldu. Takımları güç sırasıyla ekleyip eşleşmeleri oluşturun."
				: "Lig oluşturuldu. Takımları ekleyip fikstürü oluşturun.");
		return "redirect:/isletme/ligler/" + id;
	}

	@GetMapping("/isletme/ligler/{id}")
	public String manage(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id, Model model) {
		TournamentQueries.Detail d = queries.forStaff(me, id);
		model.addAttribute("d", d);
		model.addAttribute("branchId", d.branchId());
		model.addAttribute("branches", branches.branchesFor(me));
		return "tournament/manage";
	}

	@PostMapping("/isletme/ligler/{id}/takimlar")
	public String addEntry(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam("ad") String name, RedirectAttributes redirect) {
		service.addEntry(me, id, name);
		redirect.addFlashAttribute("flashSuccess", "Takım eklendi.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/ligler/{id}/takimlar/{entryId}/sil")
	public String removeEntry(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@PathVariable Long entryId, RedirectAttributes redirect) {
		service.removeEntry(me, id, entryId);
		redirect.addFlashAttribute("flashSuccess", "Takım çıkarıldı.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/ligler/{id}/ucunculuk")
	public String thirdPlace(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam(name = "acik", defaultValue = "false") boolean on, RedirectAttributes redirect) {
		service.changeThirdPlace(me, id, on);
		redirect.addFlashAttribute("flashSuccess", on ? "Üçüncülük maçı eklendi: yarı finalde kaybedenler oynar."
				: "Üçüncülük maçı kaldırıldı.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/ligler/{id}/baslat")
	public String start(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		int n = service.start(me, id);
		redirect.addFlashAttribute("flashSuccess", "Fikstür oluşturuldu: " + n + " maç. Şimdi maçları planlayın.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/ligler/{id}/bitir")
	public String finish(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		service.finish(me, id);
		redirect.addFlashAttribute("flashSuccess", "Lig tamamlandı.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/ligler/{id}/haftalik-planla")
	public String planWeekly(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam("tarih") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firstDate,
			@RequestParam("saat") @DateTimeFormat(pattern = "HH:mm") LocalTime start,
			@RequestParam(name = "sure", defaultValue = "60") int minutes, @RequestParam("saha") Long pitchId,
			RedirectAttributes redirect) {
		int n = service.planWeekly(me, id, firstDate, start, minutes, pitchId);
		redirect.addFlashAttribute("flashSuccess", n + " maç planlandı ve sahalar takvimde ayrıldı.");
		return "redirect:/isletme/ligler/" + id;
	}

	@PostMapping("/isletme/maclar/{matchId}/planla")
	public String schedule(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam Long lig, @RequestParam("saha") Long pitchId,
			@RequestParam("tarih") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam("saat") @DateTimeFormat(pattern = "HH:mm") LocalTime time,
			@RequestParam(name = "sure", defaultValue = "60") int minutes, RedirectAttributes redirect) {
		service.schedule(me, matchId, pitchId, date.atTime(time), minutes);
		redirect.addFlashAttribute("flashSuccess", "Maç planlandı.");
		return "redirect:/isletme/ligler/" + lig + "#mac-" + matchId;
	}

	@PostMapping("/isletme/maclar/{matchId}/plani-kaldir")
	public String unschedule(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam Long lig, RedirectAttributes redirect) {
		service.unschedule(me, matchId);
		redirect.addFlashAttribute("flashSuccess", "Maçın planı kaldırıldı; saha boşa çıktı.");
		return "redirect:/isletme/ligler/" + lig + "#mac-" + matchId;
	}

	@PostMapping("/isletme/maclar/{matchId}/skor")
	public String result(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long matchId,
			@RequestParam Long lig, @RequestParam(name = "ev", required = false) Integer home,
			@RequestParam(name = "dep", required = false) Integer away,
			@RequestParam(name = "penalti", required = false) Long penaltyWinner, RedirectAttributes redirect) {
		service.recordResult(me, matchId, home, away, penaltyWinner);
		redirect.addFlashAttribute("flashSuccess", "Skor kaydedildi.");
		return "redirect:/isletme/ligler/" + lig + "#mac-" + matchId;
	}

}
