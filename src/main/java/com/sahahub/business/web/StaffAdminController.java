package com.sahahub.business.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.StaffAdminService;
import com.sahahub.business.app.StaffBranchService;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;

/**
 * İşletme sahibinin ekranları: personel ve yetkiler, denetim kayıtları. "sube" parametresi yalnızca
 * şube menüsünü doğru göstermek içindir; yetki işletme düzeyinde kontrol edilir.
 */
@Controller
public class StaffAdminController {

	private final StaffAdminService service;
	private final StaffBranchService branches;

	public StaffAdminController(StaffAdminService service, StaffBranchService branches) {
		this.service = service;
		this.branches = branches;
	}

	@GetMapping("/isletme/isletmeler/{businessId}/personel")
	public String staff(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long businessId,
			@RequestParam(name = "sube") Long branchId, Model model) {
		model.addAttribute("rows", service.staff(me, businessId));
		model.addAttribute("businessId", businessId);
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("businessBranches",
				branches.branchesFor(me).stream().filter(b -> b.businessId().equals(businessId)).toList());
		model.addAttribute("roles", StaffRole.values());
		return "admin/staff";
	}

	@PostMapping("/isletme/isletmeler/{businessId}/personel")
	public String assign(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long businessId,
			@RequestParam(name = "sube") Long branchId, @RequestParam("eposta") String email,
			@RequestParam("rol") StaffRole role, @RequestParam(name = "gorevSube", required = false) Long scope,
			RedirectAttributes redirect) {
		service.assign(me, businessId, email, role, scope);
		redirect.addFlashAttribute("flashSuccess",
				"Görev verildi. Kişi menüde yeni yetkileri bir sonraki girişinde görür.");
		return "redirect:/isletme/isletmeler/" + businessId + "/personel?sube=" + branchId;
	}

	@PostMapping("/isletme/isletmeler/{businessId}/personel/{membershipId}/kaldir")
	public String revoke(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long businessId,
			@PathVariable Long membershipId, @RequestParam(name = "sube") Long branchId, RedirectAttributes redirect) {
		service.revoke(me, businessId, membershipId);
		redirect.addFlashAttribute("flashSuccess", "Görev kaldırıldı. Yetki hemen geçersiz olur.");
		return "redirect:/isletme/isletmeler/" + businessId + "/personel?sube=" + branchId;
	}

	@GetMapping("/isletme/isletmeler/{businessId}/denetim")
	public String audit(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long businessId,
			@RequestParam(name = "sube") Long branchId, @RequestParam(name = "islem", required = false) String action,
			@RequestParam(name = "bas", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "bit", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "sayfa", defaultValue = "0") int page, Model model) {
		model.addAttribute("p", service.audit(me, businessId, action, from, to, page));
		model.addAttribute("businessId", businessId);
		model.addAttribute("branchId", branchId);
		model.addAttribute("action", action);
		model.addAttribute("from", from);
		model.addAttribute("to", to);
		model.addAttribute("branches", branches.branchesFor(me));
		return "admin/audit";
	}

}
