package com.sahahub.booking.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.servlet.support.RequestContextUtils;

import com.sahahub.booking.app.CalendarView;
import com.sahahub.booking.app.PitchBlockService;
import com.sahahub.booking.app.ReservationView;
import com.sahahub.booking.app.StaffCalendarService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.business.app.StaffBranchService;
import com.sahahub.business.app.StaffBranchService.BranchRef;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.web.Htmx;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * İşletme paneli: günlük/haftalık takvim, hızlı rezervasyon, rezervasyon işlemleri, saha kapatma.
 * Yetki kontrolleri servis katmanında yapılır; bu sınıf yalnızca HTTP ile servisler arasında çeviri yapar.
 */
@Controller
public class StaffController {

	private final StaffBranchService branches;
	private final StaffCalendarService calendar;
	private final StaffReservationService reservations;
	private final PitchBlockService blocks;

	public StaffController(StaffBranchService branches, StaffCalendarService calendar,
			StaffReservationService reservations, PitchBlockService blocks) {
		this.branches = branches;
		this.calendar = calendar;
		this.reservations = reservations;
		this.blocks = blocks;
	}

	@GetMapping("/isletme")
	public String home(@AuthenticationPrincipal AppUserPrincipal me, Model model) {
		List<BranchRef> mine = branches.branchesFor(me);
		if (mine.isEmpty()) {
			model.addAttribute("branches", mine);
			return "staff/no-branch";
		}
		return "redirect:/isletme/subeler/" + mine.getFirst().id() + "/takvim";
	}

	@GetMapping("/isletme/subeler/{branchId}/takvim")
	public String calendar(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(name = "tarih", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			@RequestParam(name = "gorunum", defaultValue = "gun") String mode,
			@RequestParam(name = "saha", required = false) Long pitchId,
			@RequestParam(name = "secili", required = false) String selectedCode, Model model) {
		CalendarView view = "hafta".equals(mode) ? calendar.week(me, branchId, pitchId, day)
				: calendar.day(me, branchId, day);
		model.addAttribute("cal", view);
		model.addAttribute("branches", branches.branchesFor(me));
		if (selectedCode != null && !selectedCode.isBlank()) {
			ReservationView r = reservations.view(me, selectedCode);
			model.addAttribute("r", r);
			model.addAttribute("moveForm", MoveForm.from(r));
		}
		return "staff/calendar";
	}

	// ------------------------------------------------------------------ hızlı rezervasyon

	@GetMapping("/isletme/subeler/{branchId}/hizli-rezervasyon")
	public String quickForm(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(name = "saha", required = false) Long pitchId,
			@RequestParam(name = "baslangic", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
			Model model, HttpServletRequest request) {
		QuickBookingForm form = new QuickBookingForm();
		form.setPitchId(pitchId);
		if (start != null) {
			form.setDate(start.toLocalDate());
			form.setTime(start.toLocalTime());
		}
		return renderQuickForm(me, branchId, form, model, request);
	}

	@PostMapping("/isletme/subeler/{branchId}/rezervasyonlar")
	public String quickCreate(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@Valid @ModelAttribute("form") QuickBookingForm form, BindingResult binding, Model model,
			HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
		if (!binding.hasErrors() && (form.getCustomerEmail() == null || form.getCustomerEmail().isBlank())
				&& (form.getGuestName() == null || form.getGuestName().isBlank())) {
			binding.rejectValue("guestName", "required", "Müşteri adını yazın ya da kayıtlı e-postasını girin.");
		}
		if (binding.hasErrors()) {
			return renderQuickForm(me, branchId, form, model, request);
		}
		String code;
		try {
			code = reservations.create(me, branchId, form.toCommand());
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("formError", ex.getMessage());
			return renderQuickForm(me, branchId, form, model, request);
		}
		LocalDate businessDay = reservations.view(me, code).businessDay();
		return redirectToCalendar(branchId, businessDay, code, "Rezervasyon oluşturuldu.", request, response,
				redirect);
	}

	private String renderQuickForm(AppUserPrincipal me, Long branchId, QuickBookingForm form, Model model,
			HttpServletRequest request) {
		CalendarView cal = calendar.day(me, branchId, form.getDate());
		model.addAttribute("cal", cal);
		model.addAttribute("form", form);
		if (Htmx.isHtmx(request)) {
			return "staff/panels :: quick-booking";
		}
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("panel", "quick-booking");
		return "staff/calendar";
	}

	// ------------------------------------------------------------------ rezervasyon ayrıntısı ve işlemler

	@GetMapping("/isletme/rezervasyonlar/{code}")
	public String detail(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code, Model model,
			HttpServletRequest request) {
		ReservationView r = reservations.view(me, code);
		if (Htmx.isHtmx(request)) {
			model.addAttribute("r", r);
			model.addAttribute("moveForm", MoveForm.from(r));
			model.addAttribute("cal", calendar.day(me, r.branchId(), r.businessDay()));
			return "staff/panels :: reservation";
		}
		return "redirect:/isletme/subeler/" + r.branchId() + "/takvim?tarih=" + r.businessDay() + "&secili="
				+ code;
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/iptal")
	public String cancel(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@RequestParam(name = "reason", required = false) String reason, RedirectAttributes redirect) {
		reservations.cancel(me, code, reason);
		redirect.addFlashAttribute("flashSuccess", "Rezervasyon iptal edildi. Saat boşa çıktı.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/gelis")
	public String checkIn(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		reservations.checkIn(me, code);
		redirect.addFlashAttribute("flashSuccess", "Geliş kaydedildi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/tamamla")
	public String complete(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		reservations.complete(me, code);
		redirect.addFlashAttribute("flashSuccess", "Maç tamamlandı olarak işaretlendi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/gelmedi")
	public String noShow(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			RedirectAttributes redirect) {
		reservations.markNoShow(me, code);
		redirect.addFlashAttribute("flashSuccess", "Müşteri gelmedi olarak işaretlendi.");
		return backTo(me, code);
	}

	@PostMapping("/isletme/rezervasyonlar/{code}/tasi")
	public String move(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable String code,
			@ModelAttribute MoveForm form, RedirectAttributes redirect) {
		if (form.getDate() == null || form.getTime() == null || form.getPitchId() == null) {
			throw new BusinessRuleException("Taşımak için saha, tarih ve saat seçin.");
		}
		reservations.move(me, code, form.getPitchId(), LocalDateTime.of(form.getDate(), form.getTime()));
		redirect.addFlashAttribute("flashSuccess", "Rezervasyon taşındı.");
		return backTo(me, code);
	}

	// ------------------------------------------------------------------ saha kapatma

	@GetMapping("/isletme/subeler/{branchId}/kapatma")
	public String blockForm(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(name = "tarih", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			Model model, HttpServletRequest request) {
		model.addAttribute("cal", calendar.day(me, branchId, day));
		model.addAttribute("reasons", PitchBlock.Reason.values());
		if (Htmx.isHtmx(request)) {
			return "staff/panels :: block";
		}
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("panel", "block");
		return "staff/calendar";
	}

	@PostMapping("/isletme/subeler/{branchId}/kapatmalar")
	public String createBlock(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam Long pitchId,
			@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
			@RequestParam PitchBlock.Reason reason, @RequestParam(required = false) String note,
			RedirectAttributes redirect) {
		blocks.create(me, pitchId, from, to, reason, note);
		redirect.addFlashAttribute("flashSuccess", "Saha belirtilen aralıkta kapatıldı.");
		return "redirect:/isletme/subeler/" + branchId + "/takvim?tarih=" + from.toLocalDate();
	}

	@PostMapping("/isletme/kapatmalar/{blockId}/kaldir")
	public String cancelBlock(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long blockId,
			@RequestParam Long branchId,
			@RequestParam(name = "tarih") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			RedirectAttributes redirect) {
		blocks.cancel(me, blockId);
		redirect.addFlashAttribute("flashSuccess", "Kapatma kaldırıldı.");
		return "redirect:/isletme/subeler/" + branchId + "/takvim?tarih=" + day;
	}

	// ------------------------------------------------------------------ yardımcılar

	private String backTo(AppUserPrincipal me, String code) {
		ReservationView r = reservations.view(me, code);
		return "redirect:/isletme/subeler/" + r.branchId() + "/takvim?tarih=" + r.businessDay() + "&secili="
				+ code;
	}

	/**
	 * HTMX isteğinde tam sayfa yönlendirmesi için HX-Redirect başlığı kullanılır. Bu yol Spring'in
	 * "redirect:" mekanizmasından geçmediği için flash mesajı elle kaydedilir.
	 */
	private static String redirectToCalendar(Long branchId, LocalDate day, String code, String message,
			HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
		String url = "/isletme/subeler/" + branchId + "/takvim?tarih=" + day + "&secili=" + code;
		if (Htmx.isHtmx(request)) {
			RequestContextUtils.getOutputFlashMap(request).put("flashSuccess", message);
			RequestContextUtils.saveOutputFlashMap(url, request, response);
			response.setHeader("HX-Redirect", url);
			return "fragments/flash :: empty";
		}
		redirect.addFlashAttribute("flashSuccess", message);
		return "redirect:" + url;
	}

}
