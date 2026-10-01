package com.sahahub.reporting.web;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.StaffBranchService;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.reporting.app.ReportService;
import com.sahahub.reporting.domain.ReportCalculator.CollectionRow;
import com.sahahub.reporting.domain.ReportCalculator.Grouping;
import com.sahahub.reporting.domain.ReportCalculator.Period;
import com.sahahub.reporting.domain.ReportCalculator.PitchUsage;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.web.CsvWriter;

/** Şube raporu (ekran + CSV) ve işletme sahibi için şube karşılaştırması. */
@Controller
public class ReportController {

	private static final Locale TR = Locale.forLanguageTag("tr");

	private final ReportService reports;
	private final StaffBranchService branches;
	private final CatalogService catalog;
	private final java.time.Clock clock;

	public ReportController(ReportService reports, StaffBranchService branches, CatalogService catalog,
			java.time.Clock clock) {
		this.reports = reports;
		this.branches = branches;
		this.catalog = catalog;
		this.clock = clock;
	}

	@GetMapping("/isletme/subeler/{branchId}/raporlar")
	public String report(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(name = "bas", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "bit", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "grup", defaultValue = "DAY") Grouping grouping, Model model) {
		Period period = period(branchId, from, to);
		ReportService.BranchReport r = reports.branchReport(me, branchId, period, grouping);
		model.addAttribute("r", r);
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		model.addAttribute("days", DayOfWeek.values());
		Long businessId = catalog.branchContext(branchId).business().getId();
		model.addAttribute("businessId", businessId);
		model.addAttribute("canCompare", branches.branchesFor(me).stream()
			.anyMatch(b -> b.businessId().equals(businessId) && b.role().name().equals("OWNER")));
		return "reporting/branch";
	}

	@GetMapping("/isletme/isletmeler/{businessId}/sube-karsilastirma")
	public String compare(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long businessId,
			@RequestParam(name = "bas", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "bit", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "sube") Long branchId, Model model) {
		Period period = period(branchId, from, to);
		model.addAttribute("rows", reports.compareBranches(me, businessId, period));
		model.addAttribute("period", period);
		model.addAttribute("businessId", businessId);
		model.addAttribute("branchId", branchId);
		model.addAttribute("branches", branches.branchesFor(me));
		return "reporting/compare";
	}

	@GetMapping("/isletme/subeler/{branchId}/raporlar.csv")
	public ResponseEntity<byte[]> csv(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long branchId,
			@RequestParam(name = "bas", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "bit", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "grup", defaultValue = "DAY") Grouping grouping,
			@RequestParam(name = "bolum") String section) {
		Period period = period(branchId, from, to);
		ReportService.BranchReport r = reports.branchReport(me, branchId, period, grouping);
		CsvWriter csv = new CsvWriter();
		switch (section) {
			case "tahsilat" -> {
				csv.header("Dönem başı", "Dönem sonu", "Nakit", "POS", "Havale", "Çevrim içi (simülasyon)",
						"Brüt tahsilat", "İade", "Ters kayıt", "Net tahsilat");
				for (CollectionRow c : r.collections()) {
					csv.text(c.start().toString()).text(c.end().toString()).number(c.cash()).number(c.pos())
						.number(c.transfer()).number(c.online()).number(c.charges()).number(c.refunds())
						.number(c.reversals()).number(c.net()).endRow();
				}
			}
			case "sahalar" -> {
				csv.header("Saha", "Açık dakika", "Kapatma dakika", "Satılabilir dakika", "Rezervasyon dakika",
						"Lig maçı dakika", "Hazırlık dakika", "Doluluk %", "Hazırlık dahil %", "Rezervasyon sayısı",
						"Rezervasyon bedeli");
				for (PitchUsage u : r.usage()) {
					csv.text(u.name()).number(u.open()).number(u.blocked()).number(u.sellable()).number(u.reserved())
						.number(u.match()).number(u.buffer()).number(u.occupancy()).number(u.busy())
						.number(u.reservations()).number(u.booked()).endRow();
				}
			}
			case "yogun-saatler" -> {
				csv.text("Gün");
				r.heatmap().hours().forEach(h -> csv.text(String.format("%02d:00", h)));
				csv.endRow();
				for (DayOfWeek d : DayOfWeek.values()) {
					csv.text(d.getDisplayName(TextStyle.FULL, TR));
					for (int i = 0; i < r.heatmap().hours().size(); i++) {
						csv.number(r.heatmap().count(d, i));
					}
					csv.endRow();
				}
			}
			case "musteriler" -> {
				csv.header("Müşteri", "Maç sayısı", "Son maç");
				r.repeat().top().forEach(v -> csv.text(v.name()).number(v.visits()).text(v.last().toString()).endRow());
			}
			default -> throw new BusinessRuleException("Bilinmeyen rapor bölümü.");
		}
		String name = "sahahub-" + section + "-" + period.from() + "_" + period.to() + ".csv";
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
			.body(csv.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Varsayılan aralık: bu ayın başından bugüne (şube saatine göre). */
	private Period period(Long branchId, LocalDate from, LocalDate to) {
		LocalDate today = LocalDate.now(clock.withZone(catalog.branchContext(branchId).branch().zone()));
		LocalDate f = from != null ? from : today.withDayOfMonth(1);
		LocalDate t = to != null ? to : today;
		try {
			return new Period(f, t);
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessRuleException(ex.getMessage());
		}
	}

}
