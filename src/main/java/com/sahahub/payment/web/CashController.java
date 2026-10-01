package com.sahahub.payment.web;

import java.math.BigDecimal;

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
import com.sahahub.payment.app.CashService;
import com.sahahub.payment.domain.CashSession;
import com.sahahub.payment.domain.Expense;
import com.sahahub.shared.domain.Money;

/** Günlük kasa ve gider ekranı. */
@Controller
public class CashController {

	private final CashService cash;
	private final StaffBranchService branches;

	public CashController(CashService cash, StaffBranchService branches) {
		this.cash = cash;
		this.branches = branches;
	}

	@GetMapping("/isletme/subeler/{branchId}/kasa")
	public String view(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		model.addAttribute("cash", cash.view(me, branchId));
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("categories", Expense.Category.values());
		return "staff/cash";
	}

	@PostMapping("/isletme/subeler/{branchId}/kasa/ac")
	public String open(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(defaultValue = "0") BigDecimal openingFloat, RedirectAttributes redirect) {
		cash.open(me, branchId, openingFloat);
		redirect.addFlashAttribute("flashSuccess", "Kasa açıldı.");
		return "redirect:/isletme/subeler/" + branchId + "/kasa";
	}

	@PostMapping("/isletme/subeler/{branchId}/kasa/kapat")
	public String close(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam BigDecimal counted, @RequestParam(required = false) String note,
			RedirectAttributes redirect) {
		CashSession s = cash.close(me, branchId, counted, note);
		int cmp = s.difference().signum();
		String diff = Money.format(s.difference().abs(), Money.DEFAULT_CURRENCY);
		redirect.addFlashAttribute(cmp == 0 ? "flashSuccess" : "flashError", cmp == 0 ? "Kasa kapatıldı, fark yok."
				: "Kasa kapatıldı. " + (cmp > 0 ? "Kasa fazlası: " : "Kasa açığı: ") + diff);
		return "redirect:/isletme/subeler/" + branchId + "/kasa";
	}

	@PostMapping("/isletme/subeler/{branchId}/giderler")
	public String expense(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam Expense.Category category, @RequestParam BigDecimal amount, @RequestParam String description,
			@RequestParam(defaultValue = "false") boolean fromCash, RedirectAttributes redirect) {
		cash.addExpense(me, branchId, category, amount, description, fromCash);
		redirect.addFlashAttribute("flashSuccess", "Gider kaydedildi.");
		return "redirect:/isletme/subeler/" + branchId + "/kasa";
	}

	@PostMapping("/isletme/subeler/{branchId}/giderler/{expenseId}/ters-kayit")
	public String reverseExpense(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@PathVariable Long expenseId, RedirectAttributes redirect) {
		cash.reverseExpense(me, expenseId);
		redirect.addFlashAttribute("flashSuccess", "Gider ters kayıtla düzeltildi.");
		return "redirect:/isletme/subeler/" + branchId + "/kasa";
	}

}
