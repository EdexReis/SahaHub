package com.sahahub.shared.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Yarı açık zaman aralığı: [start, end) — başlangıç dahil, bitiş hariç.
 * <p>
 * Bu tanım sayesinde 20:00-21:00 ile 21:00-22:00 aralıkları çakışmaz:
 * ilkinin bitişi (21:00) aralığa dahil değildir. PostgreSQL'deki tstzrange(..., '[)')
 * ile birebir aynı kuraldır.
 */
public record TimeRange(Instant start, Instant end) {

	public TimeRange {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
		if (!end.isAfter(start)) {
			throw new IllegalArgumentException("Bitiş başlangıçtan sonra olmalı: " + start + " / " + end);
		}
	}

	/** İki aralık en az bir an paylaşıyor mu? */
	public boolean overlaps(TimeRange other) {
		return start.isBefore(other.end) && other.start.isBefore(end);
	}

	/** other aralığı tamamen bu aralığın içinde mi? */
	public boolean contains(TimeRange other) {
		return !other.start.isBefore(start) && !other.end.isAfter(end);
	}

	public TimeRange extendEnd(Duration extra) {
		return new TimeRange(start, end.plus(extra));
	}

	public long minutes() {
		return Duration.between(start, end).toMinutes();
	}

}
