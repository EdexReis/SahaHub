package com.sahahub.booking.web;

import java.time.Instant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.booking.app.WaitlistService;
import com.sahahub.identity.security.AppUserPrincipal;

/** Müşteri: dolu saat için sıraya girme ve sıradan çıkma. Liste "Rezervasyonlarım" sayfasındadır. */
@Controller
public class WaitlistController {

	private final WaitlistService waitlist;

	public WaitlistController(WaitlistService waitlist) {
		this.waitlist = waitlist;
	}

	@PostMapping("/bekleme-listesi")
	public String join(@AuthenticationPrincipal AppUserPrincipal me, @RequestParam Long pitchId,
			@RequestParam("baslangic") Instant start, RedirectAttributes redirect) {
		waitlist.join(me, pitchId, start);
		redirect.addFlashAttribute("flashSuccess", "Sıraya girdiniz. Saat boşalırsa "
				+ waitlist.offerDuration().toMinutes() + " dakika sizin için tutulur ve bildirim alırsınız.");
		return "redirect:/rezervasyonlarim#bekleme";
	}

	@PostMapping("/bekleme-listesi/{id}/birak")
	public String leave(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id,
			RedirectAttributes redirect) {
		waitlist.leave(me, id);
		redirect.addFlashAttribute("flashSuccess", "Sıradan çıktınız.");
		return "redirect:/rezervasyonlarim#bekleme";
	}

}
