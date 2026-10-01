package com.sahahub.payment.web;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sahahub.booking.domain.Reservation;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.OnlinePaymentService;
import com.sahahub.payment.provider.SimulatedPaymentProvider;
import com.sahahub.payment.provider.SimulationScenario;
import com.sahahub.payment.provider.SimulationWebhookDispatcher;

/**
 * DEMO: Simülasyon sağlayıcısının "ödeme sayfası". Gerçek bir sağlayıcıda bu sayfa sağlayıcının kendi
 * sitesinde olurdu. Kart alanı yoktur; müşteri yalnızca denenecek sonucu seçer.
 */
@Controller
public class SimulationProviderController {

	private final SimulatedPaymentProvider provider;
	private final SimulationWebhookDispatcher dispatcher;
	private final OnlinePaymentService online;

	public SimulationProviderController(SimulatedPaymentProvider provider, SimulationWebhookDispatcher dispatcher,
			OnlinePaymentService online) {
		this.provider = provider;
		this.dispatcher = dispatcher;
		this.online = online;
	}

	@GetMapping("/odeme-saglayici/simulasyon/{ref}")
	public String page(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String ref, Model model) {
		Reservation r = online.reservationOfProviderRef(me, ref);
		Map<String, Object> charge = provider.charge(ref);
		model.addAttribute("ref", ref);
		model.addAttribute("charge", charge);
		model.addAttribute("code", r.getCode());
		model.addAttribute("scenarios", SimulationScenario.values());
		return "payment/simulation";
	}

	@PostMapping("/odeme-saglayici/simulasyon/{ref}")
	public String complete(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String ref,
			@RequestParam SimulationScenario scenario) {
		Reservation r = online.reservationOfProviderRef(me, ref);
		provider.complete(ref, scenario);
		// Gecikmesiz bildirimleri hemen teslim et (gerçek sağlayıcıda bildirim ayrı bir HTTP isteğiyle gelir)
		dispatcher.deliverDue();
		return "redirect:/rezervasyon/" + r.getCode() + "?odeme=" + scenario.name().toLowerCase(java.util.Locale.ROOT);
	}

}
