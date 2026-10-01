package com.sahahub.pricing.web;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.StaffBranchService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.app.PricingAdminService;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.DepositPolicy;

/** Fiyatlandırma ekranı: taban ücret, kurallar, ek hizmetler, kapora ve kuponlar. */
@Controller
public class PricingController {

	private final PricingAdminService pricing;
	private final StaffBranchService branches;

	public PricingController(PricingAdminService pricing, StaffBranchService branches) {
		this.pricing = pricing;
		this.branches = branches;
	}

	@GetMapping("/isletme/subeler/{branchId}/fiyatlar")
	public String view(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		model.addAttribute("pricing", pricing.view(me, branchId));
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("days", DayOfWeek.values());
		model.addAttribute("depositTypes", DepositPolicy.Type.values());
		return "staff/pricing";
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/taban")
	public String basePrice(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam Long pitchId, @RequestParam BigDecimal price, RedirectAttributes redirect) {
		pricing.changeBasePrice(me, branchId, pitchId, price);
		return done(branchId, "Taban ücret güncellendi. Mevcut rezervasyonlar etkilenmez.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/kurallar")
	public String addRule(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam Long pitchId, @RequestParam String name,
			@RequestParam(name = "days", required = false) List<DayOfWeek> days,
			@RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime start,
			@RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime end,
			@RequestParam BigDecimal hourlyPrice, @RequestParam(defaultValue = "10") int priority,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo,
			RedirectAttributes redirect) {
		Set<DayOfWeek> set = days == null || days.isEmpty() ? Set.of() : EnumSet.copyOf(days);
		pricing.addRule(me, branchId, new PricingAdminService.RuleCommand(pitchId, name, set, start, end, hourlyPrice,
				priority, validFrom, validTo));
		return done(branchId, "Fiyat kuralı eklendi.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/kurallar/{ruleId}/kaldir")
	public String removeRule(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@PathVariable Long ruleId, RedirectAttributes redirect) {
		pricing.deactivateRule(me, branchId, ruleId);
		return done(branchId, "Fiyat kuralı kaldırıldı.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/ek-hizmetler")
	public String addExtra(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam String name, @RequestParam BigDecimal price, RedirectAttributes redirect) {
		pricing.addExtra(me, branchId, name, price);
		return done(branchId, "Ek hizmet eklendi.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/ek-hizmetler/{extraId}/kaldir")
	public String removeExtra(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@PathVariable Long extraId, RedirectAttributes redirect) {
		pricing.deactivateExtra(me, branchId, extraId);
		return done(branchId, "Ek hizmet kaldırıldı.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/kapora")
	public String deposit(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam DepositPolicy.Type type, @RequestParam(required = false) BigDecimal value,
			@RequestParam(required = false) String iban, RedirectAttributes redirect) {
		pricing.changeDeposit(me, branchId, type, value, iban);
		return done(branchId, "Kapora kuralı güncellendi. Yalnızca yeni rezervasyonlara uygulanır.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/kuponlar")
	public String addCoupon(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam String code, @RequestParam Coupon.Kind kind, @RequestParam BigDecimal value,
			@RequestParam int maxUses,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo,
			RedirectAttributes redirect) {
		pricing.addCoupon(me, branchId,
				new PricingAdminService.CouponCommand(code, kind, value, maxUses, validFrom, validTo));
		return done(branchId, "Kupon oluşturuldu.", redirect);
	}

	@PostMapping("/isletme/subeler/{branchId}/fiyatlar/kuponlar/{couponId}/kaldir")
	public String removeCoupon(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@PathVariable Long couponId, RedirectAttributes redirect) {
		pricing.deactivateCoupon(me, branchId, couponId);
		return done(branchId, "Kupon devre dışı bırakıldı.", redirect);
	}

	private static String done(Long branchId, String message, RedirectAttributes redirect) {
		redirect.addFlashAttribute("flashSuccess", message);
		return "redirect:/isletme/subeler/" + branchId + "/fiyatlar";
	}

}
