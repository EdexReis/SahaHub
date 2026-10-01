package com.sahahub.payment.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.PaymentPanelService;

/**
 * Yazdırılabilir "Rezervasyon / ödeme özeti". Fatura veya mali belge DEĞİLDİR; sayfada da açıkça yazar.
 */
@Controller
public class ReceiptController {

	private final CustomerBookingService customerBooking;
	private final StaffReservationService staffBooking;
	private final PaymentPanelService panels;

	public ReceiptController(CustomerBookingService customerBooking, StaffReservationService staffBooking,
			PaymentPanelService panels) {
		this.customerBooking = customerBooking;
		this.staffBooking = staffBooking;
		this.panels = panels;
	}

	@GetMapping("/rezervasyon/{code}/ozet")
	public String customer(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code, Model model) {
		model.addAttribute("r", customerBooking.view(me, code));
		model.addAttribute("pay", panels.forCustomer(me, code));
		return "payment/receipt";
	}

	@GetMapping("/isletme/rezervasyonlar/{code}/ozet")
	public String staff(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code, Model model) {
		model.addAttribute("r", staffBooking.view(me, code));
		model.addAttribute("pay", panels.forStaff(me, code));
		return "payment/receipt";
	}

}
