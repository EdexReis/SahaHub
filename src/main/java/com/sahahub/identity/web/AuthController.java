package com.sahahub.identity.web;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.sahahub.identity.app.RegistrationService;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Controller
public class AuthController {

	private final RegistrationService registration;
	private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

	public AuthController(RegistrationService registration) {
		this.registration = registration;
	}

	@GetMapping("/giris")
	public String login() {
		return "auth/login";
	}

	@GetMapping("/kayit")
	public String registerForm(Model model) {
		model.addAttribute("form", new RegistrationForm());
		return "auth/register";
	}

	@PostMapping("/kayit")
	public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult binding,
			HttpServletRequest request, HttpServletResponse response) {
		if (binding.hasErrors()) {
			return "auth/register";
		}
		AppUser user;
		try {
			user = registration.registerCustomer(form.getEmail(), form.getPassword(), form.getFullName(),
					form.getPhone());
		}
		catch (BusinessRuleException ex) {
			binding.rejectValue("email", "duplicate", ex.getMessage());
			return "auth/register";
		}
		// Kayıttan sonra otomatik giriş; oturum kimliği yenilenir
		request.changeSessionId();
		AppUserPrincipal principal = AppUserPrincipal.of(user, false);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return "redirect:/sahalar?hosgeldin";
	}

}
