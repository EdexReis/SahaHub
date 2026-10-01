package com.sahahub.booking.web;

import java.util.ArrayList;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.booking.app.ReservationView;
import com.sahahub.booking.app.SeriesService;
import com.sahahub.booking.app.StaffCalendarService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.business.app.StaffBranchService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.validation.Valid;

/**
 * Düzenli (haftalık) rezervasyon: form → önizleme (her tarihin durumu) → oluştur.
 * Müşteri bilgileri adres çubuğuna yazılmasın diye önizleme de POST ile yapılır.
 */
@Controller
public class SeriesController {

	private final SeriesService series;
	private final StaffCalendarService calendar;
	private final StaffBranchService branches;
	private final StaffReservationService reservations;

	public SeriesController(SeriesService series, StaffCalendarService calendar, StaffBranchService branches,
			StaffReservationService reservations) {
		this.series = series;
		this.calendar = calendar;
		this.branches = branches;
		this.reservations = reservations;
	}

	@GetMapping("/isletme/subeler/{branchId}/duzenli")
	public String form(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		return render(me, branchId, new SeriesForm(), null, model);
	}

	@PostMapping("/isletme/subeler/{branchId}/duzenli/onizleme")
	public String preview(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@Valid @ModelAttribute("form") SeriesForm form, BindingResult binding, Model model) {
		if (!validate(form, binding)) {
			return render(me, branchId, form, null, model);
		}
		try {
			SeriesService.Preview p = series.preview(me, branchId, form.toSeriesCommand());
			// Varsayılan seçim: uygun olan tüm tarihler
			form.setChosen(new ArrayList<>(p.items().stream()
				.filter(SeriesService.Occurrence::available)
				.map(SeriesService.Occurrence::date)
				.toList()));
			return render(me, branchId, form, p, model);
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("formError", ex.getMessage());
			return render(me, branchId, form, null, model);
		}
	}

	@PostMapping("/isletme/subeler/{branchId}/duzenli")
	public String create(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@Valid @ModelAttribute("form") SeriesForm form, BindingResult binding,
			@RequestParam(name = "mod") SeriesService.Mode mode, Model model, RedirectAttributes redirect) {
		if (!validate(form, binding)) {
			return render(me, branchId, form, null, model);
		}
		try {
			Long seriesId = series.create(me, branchId, form.toSeriesCommand(), mode, form.getChosen());
			String firstCode = series.firstCode(seriesId);
			ReservationView r = reservations.view(me, firstCode);
			redirect.addFlashAttribute("flashSuccess", "Düzenli rezervasyon oluşturuldu.");
			return "redirect:/isletme/subeler/" + branchId + "/takvim?tarih=" + r.businessDay() + "&secili="
					+ firstCode;
		}
		catch (BusinessRuleException ex) {
			// Ör. önizlemeden sonra bir tarih doldu: hiçbir maç oluşmadı; güncel önizleme gösterilir
			model.addAttribute("formError", ex.getMessage());
			SeriesService.Preview p = null;
			try {
				p = series.preview(me, branchId, form.toSeriesCommand());
			}
			catch (BusinessRuleException ignored) {
				// Form hatası zaten gösteriliyor
			}
			return render(me, branchId, form, p, model);
		}
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/seri-iptal")
	public String cancelFrom(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam(name = "reason", required = false) String reason, RedirectAttributes redirect) {
		int n = series.cancelFrom(me, code, reason);
		redirect.addFlashAttribute("flashSuccess", n + " maç iptal edildi. Saatler boşa çıktı.");
		ReservationView r = reservations.view(me, code);
		return "redirect:/isletme/subeler/" + r.branchId() + "/takvim?tarih=" + r.businessDay() + "&secili=" + code;
	}

	private static boolean validate(SeriesForm form, BindingResult binding) {
		if (!binding.hasErrors() && (form.getCustomerEmail() == null || form.getCustomerEmail().isBlank())
				&& (form.getGuestName() == null || form.getGuestName().isBlank())) {
			binding.rejectValue("guestName", "required", "Müşteri adını yazın ya da kayıtlı e-postasını girin.");
		}
		return !binding.hasErrors();
	}

	private String render(AppUserPrincipal me, Long branchId, SeriesForm form, SeriesService.Preview preview,
			Model model) {
		model.addAttribute("cal", calendar.day(me, branchId, null));
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("form", form);
		model.addAttribute("preview", preview);
		model.addAttribute("maxOccurrences", series.maxOccurrences());
		return "staff/series";
	}

}
