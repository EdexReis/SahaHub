package com.sahahub.platform.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.platform.app.PlatformAdminService;

/** Platform yöneticisi ekranı: işletme listesi ve askıya alma/etkinleştirme. */
@Controller
public class PlatformController {

	private final PlatformAdminService service;

	public PlatformController(PlatformAdminService service) {
		this.service = service;
	}

	@GetMapping("/admin/isletmeler")
	public String list(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		model.addAttribute("rows", service.businesses(me));
		return "admin/businesses";
	}

	@PostMapping("/admin/isletmeler/{id}/durum")
	public String changeStatus(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			@RequestParam("islem") String action, @RequestParam(name = "reason", required = false) String reason,
			RedirectAttributes redirect) {
		String name = service.changeStatus(me, id, "askiya-al".equals(action), reason);
		redirect.addFlashAttribute("flashSuccess", name + " durumu güncellendi.");
		return "redirect:/admin/isletmeler";
	}

}
