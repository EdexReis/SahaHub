package com.sahahub.booking.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sahahub.booking.app.AvailabilityService;
import com.sahahub.booking.app.AvailabilityService.DayAvailability;
import com.sahahub.business.app.CatalogService;

/** Herkese açık uygunluk bilgisi (JSON). Yanıt DTO'dur; entity dışarı verilmez, kişisel veri içermez. */
@RestController
public class AvailabilityApiController {

	public record SlotDto(Instant start, Instant end, String state, BigDecimal price, String currency) {
	}

	public record DayDto(Long pitchId, LocalDate date, String status, String timeZone, List<SlotDto> slots) {
	}

	private final CatalogService catalog;
	private final AvailabilityService availability;

	public AvailabilityApiController(CatalogService catalog, AvailabilityService availability) {
		this.catalog = catalog;
		this.availability = availability;
	}

	@GetMapping("/api/sahalar/{pitchId}/uygunluk")
	public DayDto availability(@PathVariable Long pitchId,
			@RequestParam("tarih") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
		var ctx = catalog.publicPitch(pitchId);
		DayAvailability da = availability.forDay(ctx, day);
		return new DayDto(pitchId, day, da.status().name(), da.zone().getId(),
				da.slots()
					.stream()
					.map(s -> new SlotDto(s.start(), s.localEnd().toInstant(), s.state().name(), s.price(),
							s.currency()))
					.toList());
	}

}
