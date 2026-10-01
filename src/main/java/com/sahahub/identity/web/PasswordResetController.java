package com.sahahub.identity.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.identity.app.PasswordResetService;
import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.servlet.http.HttpServletRequest;

/** "Parolamı unuttum" ve sıfırlama bağlantısı. Yanıtlar hesabın var olup olmadığını belli etmez. */
@Controller
public class PasswordResetController {

	static final String SENT = "Bu e-posta kayıtlıysa sıfırlama bağlantısı gönderildi. Bağlantı 30 dakika geçerlidir.";

	private final PasswordResetService service;

	public PasswordResetController(PasswordResetService service) {
		this.service = service;
	}

	@GetMapping("/parolami-unuttum")
	public String forgotForm() {
		return "auth/forgot";
	}

	@PostMapping("/parolami-unuttum")
	public String forgot(@RequestParam(name = "email", defaultValue = "") String email, HttpServletRequest request,
			RedirectAttributes redirect) {
		service.request(email, request.getRemoteAddr());
		redirect.addFlashAttribute("flashSuccess", SENT);
		return "redirect:/parolami-unuttum";
	}

	@GetMapping("/parola-sifirla/{token}")
	public String resetForm(@PathVariable String token, Model model) {
		model.addAttribute("token", token);
		model.addAttribute("usable", service.isUsable(token));
		return "auth/reset";
	}

	@PostMapping("/parola-sifirla/{token}")
	public String reset(@PathVariable String token, @RequestParam(name = "password", defaultValue = "") String password,
			@RequestParam(name = "repeat", defaultValue = "") String repeat, Model model,
			RedirectAttributes redirect) {
		try {
			service.reset(token, password, repeat);
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("token", token);
			model.addAttribute("usable", service.isUsable(token));
			model.addAttribute("formError", ex.getMessage());
			return "auth/reset";
		}
		redirect.addFlashAttribute("flashSuccess", "Parolanız değişti. Yeni parolanızla giriş yapın.");
		return "redirect:/giris";
	}

}
