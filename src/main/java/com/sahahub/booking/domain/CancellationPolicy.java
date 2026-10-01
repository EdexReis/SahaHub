package com.sahahub.booking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Müşteri iptal kuralı.
 * <p>
 * Sınır anı DAHİLDİR: iptal süresi 24 saat ve maç 2 Ekim 21:00'deyse müşteri
 * 1 Ekim 21:00:00'da hâlâ iptal edebilir; 21:00:01'de edemez.
 * Geçici tutulan (HELD) bir rezervasyon her zaman serbest bırakılabilir.
 */
public final class CancellationPolicy {

	private CancellationPolicy() {
	}

	public static Instant customerDeadline(Instant startsAt, Duration cutoff) {
		return startsAt.minus(cutoff);
	}

	public static boolean customerMayCancel(ReservationStatus status, Instant startsAt, Duration cutoff,
			Instant now) {
		if (status == ReservationStatus.HELD) {
			return true;
		}
		return status == ReservationStatus.CONFIRMED && !now.isAfter(customerDeadline(startsAt, cutoff));
	}

}
