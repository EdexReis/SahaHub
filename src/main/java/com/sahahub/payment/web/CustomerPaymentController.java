package com.sahahub.payment.web;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.booking.app.ReservationPricingService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.OnlinePaymentService;
import com.sahahub.payment.app.PaymentService;

/**
 * Müşterinin rezervasyon sayfasındaki ödeme ve fiyat işlemleri. İş kuralları ve sahiplik kontrolü
 * servislerde; burada yalnızca HTTP çevirisi var. Hatalar (BusinessRuleException) mesajla aynı
 * sayfaya döner.
 */
@Controller
public class CustomerPaymentController {

	private final ReservationPricingService pricing;
	private final OnlinePaymentService online;
	private final PaymentService payments;

	public CustomerPaymentController(ReservationPricingService pricing, OnlinePaymentService online,
			PaymentService payments) {
		this.pricing = pricing;
		this.online = online;
		this.payments = payments;
	}

	@PostMapping("/rezervasyon/{code}/ek-hizmet")
	public String addExtra(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam Long extraId, @RequestParam(defaultValue = "1") int quantity, RedirectAttributes redirect) {
		pricing.addExtra(me, code, extraId, quantity);
		redirect.addFlashAttribute("flashSuccess", "Ek hizmet eklendi.");
		return "redirect:/rezervasyon/" + code;
	}

	@PostMapping("/rezervasyon/{code}/kalem/{lineId}/kaldir")
	public String removeLine(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@PathVariable Long lineId, RedirectAttributes redirect) {
		pricing.voidLine(me, code, lineId);
		redirect.addFlashAttribute("flashSuccess", "Kalem kaldırıldı.");
		return "redirect:/rezervasyon/" + code;
	}

	@PostMapping("/rezervasyon/{code}/kupon")
	public String coupon(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam("kupon") String couponCode, RedirectAttributes redirect) {
		pricing.applyCoupon(me, code, couponCode);
		redirect.addFlashAttribute("flashSuccess", "Kupon uygulandı.");
		return "redirect:/rezervasyon/" + code;
	}

	/** Sağlayıcının (simülasyon) ödeme sayfasına yönlendirir. Kart bilgisi bu uygulamaya hiç gelmez. */
	@PostMapping("/rezervasyon/{code}/odeme")
	public String pay(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam("secenek") OnlinePaymentService.Option option, @RequestParam("anahtar") String key) {
		return "redirect:" + online.start(me, code, option, key);
	}

	@PostMapping("/rezervasyon/{code}/havale")
	public String transfer(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam BigDecimal amount, @RequestParam String payerName, @RequestParam("anahtar") String key,
			RedirectAttributes redirect) {
		payments.notifyTransfer(me, code, amount, payerName, key);
		redirect.addFlashAttribute("flashSuccess", "Havale bildiriminiz alındı. Şube doğruladığında ödenmiş görünecek.");
		return "redirect:/rezervasyon/" + code;
	}

}
