package com.sahahub.notification.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.notification.app.NotificationService;

/** Bildirim kutusu, bildirim tercihleri (profil) ve yöneticinin demo SMS/WhatsApp kutusu. */
@Controller
public class NotificationController {

	private final NotificationService service;

	public NotificationController(NotificationService service) {
		this.service = service;
	}

	@GetMapping("/bildirimler")
	public String inbox(@AuthenticationPrincipal AppUserPrincipal me,
			@RequestParam(name = "sayfa", defaultValue = "0") int page, Model model) {
		model.addAttribute("page", service.inbox(me.id(), page));
		return "notification/inbox";
	}

	@PostMapping("/bildirimler/okundu")
	public String markAllRead(@AuthenticationPrincipal AppUserPrincipal me, RedirectAttributes redirect) {
		int n = service.markAllRead(me.id());
		redirect.addFlashAttribute("flashSuccess", n == 0 ? "Okunmamış bildirim yoktu." : n + " bildirim okundu olarak işaretlendi.");
		return "redirect:/bildirimler";
	}

	@GetMapping("/profil")
	public String profile(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		model.addAttribute("prefs", service.preferences(me.id()));
		return "notification/profile";
	}

	@PostMapping("/profil/bildirimler")
	public String updatePreferences(@AuthenticationPrincipal AppUserPrincipal me,
			@RequestParam(name = "eposta", defaultValue = "false") boolean email,
			@RequestParam(name = "sms", defaultValue = "false") boolean sms,
			@RequestParam(name = "telefon", defaultValue = "") String phone, RedirectAttributes redirect) {
		service.updatePreferences(me.id(), email, sms, phone);
		redirect.addFlashAttribute("flashSuccess", "Bildirim tercihleriniz kaydedildi.");
		return "redirect:/profil";
	}

	@GetMapping("/admin/demo-mesajlar")
	public String demoMessages(Model model) {
		model.addAttribute("messages", service.demoMessages());
		return "notification/demo-messages";
	}

}
