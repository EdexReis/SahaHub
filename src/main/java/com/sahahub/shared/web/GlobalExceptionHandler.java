package com.sahahub.shared.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sayfa (HTML) controller'ları için merkezi hata yönetimi.
 * Yetki hataları (AccessDeniedException) burada yakalanmaz; Spring Security
 * onları 403 sayfasına yönlendirir. Beklenmeyen hatalar Spring Boot'un
 * error/500 sayfasına düşer (ayrıntı kullanıcıya gösterilmez, loglanır).
 */
@ControllerAdvice(annotations = Controller.class)
public class GlobalExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public String notFound(NotFoundException ex, Model model) {
		model.addAttribute("message", ex.getMessage());
		return "error/404";
	}

	/**
	 * İş kuralı ihlali: HTMX isteğinde sayfadaki uyarı alanına mesaj parçası döner,
	 * normal istekte kullanıcıyı geldiği sayfaya mesajla geri gönderir.
	 */
	@ExceptionHandler(BusinessRuleException.class)
	public String businessRule(BusinessRuleException ex, HttpServletRequest request, HttpServletResponse response,
			Model model, RedirectAttributes redirect) {
		if (Htmx.isHtmx(request)) {
			response.setHeader("HX-Retarget", "#flash-area");
			response.setHeader("HX-Reswap", "innerHTML");
			model.addAttribute("flashError", ex.getMessage());
			return "fragments/flash :: messages";
		}
		redirect.addFlashAttribute("flashError", ex.getMessage());
		return "redirect:" + safeBackPath(request);
	}

	/**
	 * Formdaki bir alan beklenen türe çevrilemedi (ör. tutar alanına "abc"). Teknik hata yerine
	 * kullanıcıya anlaşılır mesaj gösterilir.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public String typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request,
			HttpServletResponse response, Model model, RedirectAttributes redirect) {
		String message = java.math.BigDecimal.class.equals(ex.getRequiredType())
				? "Geçerli bir tutar girin (ör. 1.250,50)."
				: "Girilen değerlerden biri geçersiz.";
		return businessRule(new BusinessRuleException(message), request, response, model, redirect);
	}

	/** Referer başlığından yalnızca yol kısmını alır; başka siteye yönlendirmeyi engeller. */
	static String safeBackPath(HttpServletRequest request) {
		String referer = request.getHeader("Referer");
		if (referer == null) {
			return "/";
		}
		try {
			URI uri = URI.create(referer);
			String path = uri.getRawPath();
			if (path == null || !path.startsWith("/") || path.startsWith("//")) {
				return "/";
			}
			return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
		}
		catch (IllegalArgumentException ex) {
			return "/";
		}
	}

}
