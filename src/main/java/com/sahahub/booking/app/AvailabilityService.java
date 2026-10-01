package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.PitchOccupancyRepository;
import com.sahahub.booking.domain.Slot;
import com.sahahub.booking.domain.SlotCalculator;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.Pitch;
import com.sahahub.pricing.domain.PriceCalculator;
import com.sahahub.pricing.domain.PriceQuote;
import com.sahahub.pricing.domain.PriceRule;
import com.sahahub.shared.domain.TimeRange;

/** Bir sahanın belirli bir gündeki saatlerini ve fiyatlarını hesaplar (yalnızca okuma). */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

	public record SlotView(Instant start, ZonedDateTime localStart, ZonedDateTime localEnd, Slot.State state,
			BigDecimal price, String currency) {

		/** Gece yarısından sonra başlayan saat (önceki iş gününe ait). */
		public boolean afterMidnight(LocalDate businessDay) {
			return localStart.toLocalDate().isAfter(businessDay);
		}

		public boolean available() {
			return state == Slot.State.AVAILABLE;
		}

	}

	public enum DayStatus {
		OPEN, CLOSED, PAST, BEYOND_HORIZON
	}

	public record DayAvailability(LocalDate day, DayStatus status, ZoneId zone, List<SlotView> slots) {

		public long availableCount() {
			return slots.stream().filter(SlotView::available).count();
		}

		/** Ekranda gösterilecek (geçmemiş) saatlerden gece yarısından önce başlayanlar. */
		public List<SlotView> eveningSlots() {
			return slots.stream().filter(s -> s.state() != Slot.State.PAST && !s.afterMidnight(day)).toList();
		}

		/** Gece yarısından sonra başlayan saatler (aynı iş gününe ait, ertesi takvim günü). */
		public List<SlotView> nightSlots() {
			return slots.stream().filter(s -> s.state() != Slot.State.PAST && s.afterMidnight(day)).toList();
		}

		public long pastCount() {
			return slots.stream().filter(s -> s.state() == Slot.State.PAST).count();
		}

	}

	public record DayChip(LocalDate day, boolean closed) {
	}

	/** Gün şeridinde gösterilecek en fazla gün sayısı. */
	private static final int MAX_STRIP_DAYS = 14;
	private static final Duration MAX_BUFFER = Duration.ofMinutes(120);

	private final CatalogService catalog;
	private final PitchOccupancyRepository occupancies;
	private final Clock clock;

	public AvailabilityService(CatalogService catalog, PitchOccupancyRepository occupancies, Clock clock) {
		this.catalog = catalog;
		this.occupancies = occupancies;
		this.clock = clock;
	}

	public LocalDate today(PitchContext ctx) {
		return LocalDate.now(clock.withZone(ctx.branch().zone()));
	}

	public DayAvailability forDay(PitchContext ctx, LocalDate day) {
		Instant now = Instant.now(clock);
		LocalDate today = today(ctx);
		ZoneId zone = ctx.branch().zone();
		if (day.isBefore(today)) {
			return new DayAvailability(day, DayStatus.PAST, zone, List.of());
		}
		if (day.isAfter(today.plusDays(ctx.branch().getBookingHorizonDays()))) {
			return new DayAvailability(day, DayStatus.BEYOND_HORIZON, zone, List.of());
		}
		BranchSchedule schedule = catalog.schedule(ctx.branch(), day, day);
		Optional<TimeRange> window = schedule.windowFor(day);
		if (window.isEmpty()) {
			return new DayAvailability(day, DayStatus.CLOSED, zone, List.of());
		}
		Pitch pitch = ctx.pitch();
		List<SlotCalculator.Busy> busy = busyOn(pitch.getId(), window.get());
		List<Slot> slots = SlotCalculator.slots(window.get(), slotConfig(pitch), busy, now);
		List<PriceRule> rules = catalog.priceRules(pitch.getId());
		List<SlotView> views = new ArrayList<>(slots.size());
		for (Slot slot : slots) {
			PriceQuote quote = PriceCalculator.quote(slot.play(), zone, pitch.getBaseHourlyPrice(),
					pitch.getCurrency(), rules);
			views.add(new SlotView(slot.play().start(), slot.play().start().atZone(zone),
					slot.play().end().atZone(zone), slot.state(), quote.total(), quote.currency()));
		}
		return new DayAvailability(day, DayStatus.OPEN, zone, views);
	}

	/** Müşterinin seçebileceği günler (bugünden itibaren, rezervasyon ufku kadar). */
	public List<DayChip> dayStrip(PitchContext ctx) {
		LocalDate today = today(ctx);
		int days = Math.min(MAX_STRIP_DAYS, ctx.branch().getBookingHorizonDays() + 1);
		BranchSchedule schedule = catalog.schedule(ctx.branch(), today, today.plusDays(days));
		List<DayChip> chips = new ArrayList<>(days);
		for (int i = 0; i < days; i++) {
			LocalDate d = today.plusDays(i);
			chips.add(new DayChip(d, schedule.windowFor(d).isEmpty()));
		}
		return chips;
	}

	/**
	 * Verilen pencereyle kesişen dolulukları getirir. Pencere, izin verilen en uzun hazırlık
	 * süresi (120 dk, şemadaki CHECK) kadar genişletilir; böylece son saatin hazırlık süresine
	 * taşan kayıtlar da görülür.
	 */
	List<SlotCalculator.Busy> busyOn(Long pitchId, TimeRange window) {
		return occupancies
			.findActiveOverlapping(List.of(pitchId), window.start(), window.end().plus(MAX_BUFFER))
			.stream()
			.map(o -> new SlotCalculator.Busy(o.range(), o.getSourceType()))
			.toList();
	}

	static SlotCalculator.Config slotConfig(Pitch pitch) {
		return new SlotCalculator.Config(Duration.ofMinutes(pitch.getSlotMinutes()),
				Duration.ofMinutes(pitch.getSlotStepMinutes()), pitch.buffer());
	}

	/** Takvimdeki dolu aralıkları (tüm kaynaklar) sahaya göre döner. */
	public List<PitchOccupancy> occupanciesFor(List<Long> pitchIds, TimeRange range) {
		return occupancies.findActiveOverlapping(pitchIds, range.start(), range.end());
	}

}
