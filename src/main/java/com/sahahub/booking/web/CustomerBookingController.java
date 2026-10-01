package com.sahahub.booking.web;

import java.time.Instant;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.WaitlistService;
import com.sahahub.booking.domain.HoldExpiredException;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.PaymentPanelService;

/** Müşterinin rezervasyon akışı: saat tut → özet → onayla; rezervasyonlarım; iptal. */
@Controller
public class CustomerBookingController {

	private static final int PAGE_SIZE = 10;

	private final CustomerBookingService booking;
	private final PaymentPanelService payments;
	private final WaitlistService waitlist;

	public CustomerBookingController(CustomerBookingService booking, PaymentPanelService payments,
			WaitlistService waitlist) {
		this.booking = booking;
		this.payments = payments;
		this.waitlist = waitlist;
	}

	/**
	 * Saat dolmuşsa (SlotUnavailableException) GlobalExceptionHandler kullanıcıyı mesajla
	 * geldiği sayfaya, yani aynı günün saat listesine geri gönderir.
	 */
	@PostMapping("/rezervasyon")
	public String hold(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam Long pitchId,
			@RequestParam("baslangic") Instant start) {
		String code = booking.hold(me, pitchId, start);
		return "redirect:/rezervasyon/" + code;
	}

	@GetMapping("/rezervasyon/{code}")
	public String view(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code, Model model) {
		model.addAttribute("r", booking.view(me, code));
		model.addAttribute("pay", payments.forCustomer(me, code));
		return "customer/reservation";
	}

	@PostMapping("/rezervasyon/{code}/onayla")
	public String confirm(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		try {
			booking.confirm(me, code);
			redirect.addFlashAttribute("flashSuccess", "Rezervasyonunuz onaylandı.");
		}
		catch (HoldExpiredException ex) {
			redirect.addFlashAttribute("flashError", ex.getMessage());
		}
		return "redirect:/rezervasyon/" + code;
	}

	@PostMapping("/rezervasyon/{code}/iptal")
	public String cancel(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		booking.cancel(me, code);
		redirect.addFlashAttribute("flashSuccess", "Rezervasyon iptal edildi, saat serbest bırakıldı.");
		return "redirect:/rezervasyon/" + code;
	}

	@GetMapping("/rezervasyonlarim")
	public String mine(@AuthenticationPrincipal AppUserPrincipal me,
			@RequestParam(name = "sayfa", defaultValue = "0") int page, Model model) {
		model.addAttribute("upcoming", booking.upcoming(me));
		model.addAttribute("page", booking.history(me, PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
		model.addAttribute("waiting", waitlist.mine(me));
		return "customer/my-reservations";
	}

}
