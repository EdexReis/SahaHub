package com.sahahub.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sahahub.shared.domain.TimeRange;

/**
 * Bir sahanın bir iş günündeki başlangıç saatlerini ve her birinin durumunu hesaplar.
 * Saf bir sınıftır; veritabanı olmadan birim testiyle denenir.
 *
 * <h2>Kurallar</h2>
 * <ul>
 * <li>Başlangıçlar açılış anından itibaren {@code step} aralıklarla dizilir.</li>
 * <li>Maç kapanıştan önce bitmelidir (bitiş = kapanış kabul edilir).</li>
 * <li>Bir saatin meşgul ettiği aralık = maç + hazırlık süresi. Bu aralık aktif bir
 * doluluk kaydıyla kesişiyorsa saat uygun değildir.</li>
 * <li>Başlangıcı geçmişte kalan saatler PAST olur.</li>
 * </ul>
 * Bu hesap yalnızca ekranda gösterim içindir. Kesin garanti, kayıt sırasında
 * veritabanındaki EXCLUDE kısıtıdır; iki kişi aynı "uygun" saati görebilir ama
 * yalnızca biri alabilir.
 */
public final class SlotCalculator {

	public record Config(Duration slotLength, Duration step, Duration buffer) {
	}

	/** Takvimdeki dolu aralık ve kaynağı (bakım bloğu mu rezervasyon mu). */
	public record Busy(TimeRange range, PitchOccupancy.Source source) {
	}

	private SlotCalculator() {
	}

	public static List<Slot> slots(TimeRange openWindow, Config config, List<Busy> busy, Instant now) {
		List<Slot> result = new ArrayList<>();
		Instant start = openWindow.start();
		while (!start.plus(config.slotLength()).isAfter(openWindow.end())) {
			TimeRange play = new TimeRange(start, start.plus(config.slotLength()));
			result.add(new Slot(play, stateOf(play, config.buffer(), busy, now)));
			start = start.plus(config.step());
		}
		return result;
	}

	static Slot.State stateOf(TimeRange play, Duration buffer, List<Busy> busy, Instant now) {
		if (!play.start().isAfter(now)) {
			return Slot.State.PAST;
		}
		TimeRange occupied = play.extendEnd(buffer);
		Slot.State state = Slot.State.AVAILABLE;
		for (Busy b : busy) {
			if (b.range().overlaps(occupied)) {
				if (b.source() == PitchOccupancy.Source.BLOCK) {
					return Slot.State.BLOCKED;
				}
				state = Slot.State.TAKEN;
			}
		}
		return state;
	}

}
