package com.sahahub.shared.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.sahahub.identity.security.AppUserPrincipal;

import jakarta.servlet.http.HttpServletRequest;

/** Her sayfanın şablonuna ortak bilgileri ekler. */
@ControllerAdvice(annotations = Controller.class)
public class CurrentUserAdvice {

	/** Oturumdaki kullanıcı; giriş yapılmamışsa null. */
	@ModelAttribute("me")
	public AppUserPrincipal me(@AuthenticationPrincipal AppUserPrincipal principal) {
		return principal;
	}

	/** Menüde aktif bağlantıyı işaretlemek için istek yolu. */
	@ModelAttribute("currentPath")
	public String currentPath(HttpServletRequest request) {
		return request.getRequestURI();
	}

}
