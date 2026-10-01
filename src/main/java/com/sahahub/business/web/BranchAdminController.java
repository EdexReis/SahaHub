package com.sahahub.business.web;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sahahub.business.app.BranchAdminService;
import com.sahahub.business.app.PitchPhotoService;
import com.sahahub.business.app.StaffBranchService;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.Surface;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;

/** Şube ayarları: sahalar (+ fotoğraf), haftalık çalışma saatleri, özel günler, rezervasyon kuralları. */
@Controller
public class BranchAdminController {

	private final BranchAdminService admin;
	private final PitchPhotoService photos;
	private final StaffBranchService branches;

	public BranchAdminController(BranchAdminService admin, PitchPhotoService photos, StaffBranchService branches) {
		this.admin = admin;
		this.photos = photos;
		this.branches = branches;
	}

	@GetMapping("/isletme/subeler/{branchId}/ayarlar")
	public String settings(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		model.addAttribute("s", admin.settings(me, branchId));
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		return "admin/branch-settings";
	}

	@PostMapping("/isletme/subeler/{branchId}/ayarlar/saatler")
	public String week(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam Map<String, String> params, RedirectAttributes redirect) {
		Map<DayOfWeek, DayHours> week = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek d : DayOfWeek.values()) {
			week.put(d, hours(params.get("kapali_" + d), params.get("acilis_" + d), params.get("kapanis_" + d)));
		}
		int outside = admin.updateWeek(me, branchId, week);
		flash(redirect, "Çalışma saatleri kaydedildi.", outside);
		return "redirect:/isletme/subeler/" + branchId + "/ayarlar#saatler";
	}

	@PostMapping("/isletme/subeler/{branchId}/ayarlar/ozel-gunler")
	public String addSpecialDay(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam("tarih") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			@RequestParam(name = "kapali", required = false) String closed,
			@RequestParam(name = "acilis", required = false) String open,
			@RequestParam(name = "kapanis", required = false) String close,
			@RequestParam(name = "not", required = false) String note, RedirectAttributes redirect) {
		int outside = admin.addSpecialDay(me, branchId, day, hours(closed, open, close), note);
		flash(redirect, "Özel gün eklendi.", outside);
		return "redirect:/isletme/subeler/" + branchId + "/ayarlar#ozel-gunler";
	}

	@PostMapping("/isletme/subeler/{branchId}/ayarlar/ozel-gunler/{id}/sil")
	public String removeSpecialDay(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@PathVariable Long id, RedirectAttributes redirect) {
		admin.removeSpecialDay(me, branchId, id);
		redirect.addFlashAttribute("flashSuccess", "Özel gün kaldırıldı; o gün haftalık saatler geçerli.");
		return "redirect:/isletme/subeler/" + branchId + "/ayarlar#ozel-gunler";
	}

	@PostMapping("/isletme/subeler/{branchId}/ayarlar/kurallar")
	public String policies(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam("tutma") int hold, @RequestParam("iptal") int cutoff, @RequestParam("ufuk") int horizon,
			RedirectAttributes redirect) {
		admin.updatePolicies(me, branchId, hold, cutoff, horizon);
		redirect.addFlashAttribute("flashSuccess", "Rezervasyon kuralları kaydedildi. Mevcut rezervasyonlar etkilenmez.");
		return "redirect:/isletme/subeler/" + branchId + "/ayarlar#kurallar";
	}

	// ------------------------------------------------------------------ saha

	@GetMapping("/isletme/subeler/{branchId}/sahalar/yeni")
	public String newPitch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId, Model model) {
		admin.settings(me, branchId); // yetki kontrolü
		return renderPitchForm(me, branchId, null, new PitchForm(), model);
	}

	@PostMapping("/isletme/subeler/{branchId}/sahalar")
	public String createPitch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@ModelAttribute("form") PitchForm form, Model model, RedirectAttributes redirect) {
		try {
			Long id = admin.createPitch(me, branchId, form.toCommand(), form.getBasePrice());
			redirect.addFlashAttribute("flashSuccess", "Saha eklendi. Fotoğraf ekleyebilirsiniz.");
			return "redirect:/isletme/sahalar/" + id + "/duzenle";
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("formError", ex.getMessage());
			return renderPitchForm(me, branchId, null, form, model);
		}
	}

	@GetMapping("/isletme/sahalar/{pitchId}/duzenle")
	public String editPitch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId, Model model) {
		Pitch p = admin.pitchForEdit(me, pitchId);
		return renderPitchForm(me, p.getBranchId(), p, PitchForm.from(p), model);
	}

	@PostMapping("/isletme/sahalar/{pitchId}")
	public String updatePitch(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId,
			@ModelAttribute("form") PitchForm form, Model model, RedirectAttributes redirect) {
		Pitch p = admin.pitchForEdit(me, pitchId);
		try {
			admin.updatePitch(me, pitchId, form.toCommand());
			redirect.addFlashAttribute("flashSuccess", "Saha kaydedildi. Mevcut rezervasyonların süresi ve hazırlığı değişmez.");
			return "redirect:/isletme/sahalar/" + pitchId + "/duzenle";
		}
		catch (BusinessRuleException ex) {
			model.addAttribute("formError", ex.getMessage());
			return renderPitchForm(me, p.getBranchId(), p, form, model);
		}
	}

	@PostMapping("/isletme/sahalar/{pitchId}/durum")
	public String setActive(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId,
			@RequestParam("aktif") boolean active, RedirectAttributes redirect) {
		admin.setPitchActive(me, pitchId, active);
		redirect.addFlashAttribute("flashSuccess", active ? "Saha rezervasyona açıldı." : "Saha rezervasyona kapatıldı.");
		return "redirect:/isletme/sahalar/" + pitchId + "/duzenle";
	}

	@PostMapping("/isletme/sahalar/{pitchId}/fotograf")
	public String upload(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId,
			@RequestParam("dosya") MultipartFile file, RedirectAttributes redirect) {
		photos.upload(me, pitchId, file);
		redirect.addFlashAttribute("flashSuccess", "Fotoğraf yüklendi.");
		return "redirect:/isletme/sahalar/" + pitchId + "/duzenle";
	}

	@PostMapping("/isletme/sahalar/{pitchId}/fotograf/sil")
	public String removePhoto(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId,
			RedirectAttributes redirect) {
		photos.remove(me, pitchId);
		redirect.addFlashAttribute("flashSuccess", "Fotoğraf kaldırıldı.");
		return "redirect:/isletme/sahalar/" + pitchId + "/duzenle";
	}

	/** Fotoğraf; erişim kuralı PitchPhotoService.read'de. Bulunamazsa 404. */
	@GetMapping("/saha-fotograf/{pitchId}")
	public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long pitchId) {
		return photos.read(me, pitchId)
			.map(bytes -> ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
				.body(bytes))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	// ------------------------------------------------------------------ yardımcılar

	private String renderPitchForm(AppUserPrincipal me, Long branchId, Pitch pitch, PitchForm form, Model model) {
		model.addAttribute("form", form);
		model.addAttribute("pitch", pitch);
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("surfaces", Surface.values());
		return "admin/pitch-form";
	}

	private static DayHours hours(String closed, String open, String close) {
		if (closed != null) {
			return DayHours.CLOSED;
		}
		try {
			return DayHours.open(LocalTime.parse(open), LocalTime.parse(close));
		}
		catch (RuntimeException ex) {
			throw new BusinessRuleException("Açık günler için açılış ve kapanış saati girin.");
		}
	}

	private static void flash(RedirectAttributes redirect, String message, int outside) {
		redirect.addFlashAttribute("flashSuccess", message);
		if (outside > 0) {
			redirect.addFlashAttribute("flashWarning", outside
					+ " ileri tarihli rezervasyon yeni saatlerin dışında kaldı. Rezervasyonlar silinmedi; takvimden kontrol edip gerekirse müşteriyle konuşun.");
		}
	}

}
