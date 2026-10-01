package com.sahahub.business.domain;

import java.time.LocalTime;

/**
 * Bir günün çalışma saatleri. close, open'dan küçük veya eşitse kapanış ertesi güne
 * sarkar: 10:00-02:00, gece 02:00'ye kadar açık demektir.
 */
public record DayHours(boolean closed, LocalTime open, LocalTime close) {

	public static final DayHours CLOSED = new DayHours(true, null, null);

	public DayHours {
		if (!closed && (open == null || close == null)) {
			throw new IllegalArgumentException("Açık gün için açılış ve kapanış saati gerekli");
		}
	}

	public static DayHours open(LocalTime open, LocalTime close) {
		return new DayHours(false, open, close);
	}

	public boolean crossesMidnight() {
		return !closed && !close.isAfter(open);
	}

}
