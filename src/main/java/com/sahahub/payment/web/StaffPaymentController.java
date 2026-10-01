package com.sahahub.payment.web;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.booking.app.ReservationPricingService;
import com.sahahub.booking.app.ReservationView;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.PaymentPanelService;
import com.sahahub.payment.app.PaymentService;
import com.sahahub.payment.domain.Payment;

/** Personelin rezervasyon panelindeki ödeme ve fiyat işlemleri. Her işlemden sonra takvim paneline döner. */
@Controller
public class StaffPaymentController {

	private final PaymentService payments;
	private final ReservationPricingService pricing;
	private final StaffReservationService reservations;
	private final PaymentPanelService panels;

	public StaffPaymentController(PaymentService payments, ReservationPricingService pricing,
			StaffReservationService reservations, PaymentPanelService panels) {
		this.payments = payments;
		this.pricing = pricing;
		this.reservations = reservations;
		this.panels = panels;
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/tahsilat")
	public String collect(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam Payment.Method method, @RequestParam BigDecimal amount, @RequestParam("anahtar") String key,
			@RequestParam(required = false) String note, RedirectAttributes redirect) {
		payments.collect(me, code, method, amount, key, note);
		redirect.addFlashAttribute("flashSuccess", "Tahsilat kaydedildi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/iade")
	public String refund(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam Long chargeId, @RequestParam BigDecimal amount, @RequestParam("anahtar") String key,
			@RequestParam(required = false) String note, RedirectAttributes redirect) {
		Payment refund = payments.refund(me, code, chargeId, amount, key, note);
		if (refund.isSucceeded()) {
			redirect.addFlashAttribute("flashSuccess", "İade kaydedildi.");
		}
		else {
			redirect.addFlashAttribute("flashError", "İade başarısız: " + refund.getFailureReason());
		}
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/ters-kayit")
	public String reverse(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam Long chargeId, @RequestParam String reason, @RequestParam("anahtar") String key,
			RedirectAttributes redirect) {
		payments.reverse(me, code, chargeId, reason, key);
		redirect.addFlashAttribute("flashSuccess", "Hatalı tahsilat ters kayıtla düzeltildi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/odemeler/{paymentId}/havale")
	public String verifyTransfer(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long paymentId,
			@RequestParam boolean accepted, @RequestParam(required = false) String reason,
			RedirectAttributes redirect) {
		payments.verifyTransfer(me, paymentId, accepted, reason);
		redirect.addFlashAttribute("flashSuccess", accepted ? "Havale doğrulandı." : "Havale bildirimi reddedildi.");
		return backTo(me, panels.reservationCodeOfPayment(me, paymentId));
	}

	/** Uyarı listesindeki ödeme hareketinden rezervasyon paneline götürür. */
	@GetMapping("/isletme/odemeler/{paymentId}")
	public String open(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long paymentId) {
		return backTo(me, panels.reservationCodeOfPayment(me, paymentId));
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/indirim")
	public String discount(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam(required = false) BigDecimal percent, @RequestParam(required = false) BigDecimal amount,
			@RequestParam(required = false) String reason, RedirectAttributes redirect) {
		pricing.applyStaffDiscount(me, code, percent, amount, reason);
		redirect.addFlashAttribute("flashSuccess", "İndirim uygulandı.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/ek-hizmet")
	public String addExtra(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam Long extraId, @RequestParam(defaultValue = "1") int quantity, RedirectAttributes redirect) {
		pricing.addExtra(me, code, extraId, quantity);
		redirect.addFlashAttribute("flashSuccess", "Ek hizmet eklendi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/kupon")
	public String coupon(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam("kupon") String couponCode, RedirectAttributes redirect) {
		pricing.applyCoupon(me, code, couponCode);
		redirect.addFlashAttribute("flashSuccess", "Kupon uygulandı.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/kalem/{lineId}/kaldir")
	public String removeLine(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@PathVariable Long lineId, RedirectAttributes redirect) {
		pricing.voidLine(me, code, lineId);
		redirect.addFlashAttribute("flashSuccess", "Kalem kaldırıldı.");
		return backTo(me, code);
	}

	private String backTo(AppUserPrincipal me, String code) {
		ReservationView r = reservations.view(me, code);
		return "redirect:/isletme/subeler/" + r.branchId() + "/takvim?tarih=" + r.businessDay() + "&secili=" + code;
	}

}
