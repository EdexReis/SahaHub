package com.sahahub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sahahub.booking.domain.PitchOccupancy.Source;
import com.sahahub.shared.domain.TimeRange;

class SlotCalculatorTest {

	static final Instant OPEN = Instant.parse("2026-03-03T15:00:00Z"); // 18:00 İstanbul
	static final TimeRange WINDOW = new TimeRange(OPEN, OPEN.plus(Duration.ofHours(4))); // 18-22
	static final Instant BEFORE = OPEN.minus(Duration.ofDays(1));

	static SlotCalculator.Config hourly(int bufferMinutes) {
		return new SlotCalculator.Config(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(bufferMinutes));
	}

	static TimeRange hours(double from, double to) {
		return new TimeRange(OPEN.plus(Duration.ofMinutes((long) (from * 60))), OPEN.plus(Duration.ofMinutes((long) (to * 60))));
	}

	@Test
	void generatesSlotsThatFitBeforeClosing() {
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(0), List.of(), BEFORE);
		assertThat(slots).hasSize(4);
		assertThat(slots).allMatch(Slot::isAvailable);
		assertThat(slots.getLast().play().end()).isEqualTo(WINDOW.end());
	}

	@Test
	void ninetyMinuteSlotsWithHourlyStepsDoNotOverrunClosing() {
		var config = new SlotCalculator.Config(Duration.ofMinutes(90), Duration.ofMinutes(60), Duration.ZERO);
		List<Slot> slots = SlotCalculator.slots(WINDOW, config, List.of(), BEFORE);
		assertThat(slots).hasSize(3); // 18:00, 19:00, 20:00 (20:00-21:30); 21:00-22:30 kapanışı aşar
	}

	@Test
	void adjacentBusyRangeDoesNotTakeSlot_halfOpenIntervals() {
		var busy = List.of(new SlotCalculator.Busy(hours(1, 2), Source.RESERVATION)); // 19-20
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(0), busy, BEFORE);
		assertThat(slots).extracting(Slot::state)
			.containsExactly(Slot.State.AVAILABLE, Slot.State.TAKEN, Slot.State.AVAILABLE, Slot.State.AVAILABLE);
	}

	@Test
	void partialOverlapTakesBothTouchedSlots() {
		var busy = List.of(new SlotCalculator.Busy(hours(1.5, 2.5), Source.RESERVATION)); // 19:30-20:30
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(0), busy, BEFORE);
		assertThat(slots).extracting(Slot::state)
			.containsExactly(Slot.State.AVAILABLE, Slot.State.TAKEN, Slot.State.TAKEN, Slot.State.AVAILABLE);
	}

	@Test
	void bufferMakesSlotBeforeABookingUnavailable() {
		var busy = List.of(new SlotCalculator.Busy(hours(2, 3), Source.RESERVATION)); // 20-21
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(15), busy, BEFORE);
		// 19:00-20:00 + 15 dk hazırlık = 20:15'e kadar → 20:00'deki maçla çakışır
		assertThat(slots.get(1).state()).isEqualTo(Slot.State.TAKEN);
	}

	@Test
	void maintenanceIsShownAsBlocked() {
		var busy = List.of(new SlotCalculator.Busy(hours(0, 2), Source.BLOCK));
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(0), busy, BEFORE);
		assertThat(slots.get(0).state()).isEqualTo(Slot.State.BLOCKED);
		assertThat(slots.get(1).state()).isEqualTo(Slot.State.BLOCKED);
		assertThat(slots.get(2).state()).isEqualTo(Slot.State.AVAILABLE);
	}

	@Test
	void slotsThatAlreadyStartedArePast() {
		List<Slot> slots = SlotCalculator.slots(WINDOW, hourly(0), List.of(), OPEN.plus(Duration.ofMinutes(61)));
		assertThat(slots).extracting(Slot::state)
			.containsExactly(Slot.State.PAST, Slot.State.PAST, Slot.State.AVAILABLE, Slot.State.AVAILABLE);
	}

}
