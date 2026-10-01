package com.sahahub.booking.app;

import java.time.Instant;

/**
 * Rezervasyon modülünün yayımladığı olaylar. Dinleyenler (bekleme listesi, bildirimler, ödeme) olayın
 * hangi aşamada işleneceğini kendisi seçer:
 * <ul>
 * <li>BEFORE_COMMIT: aynı transaction içinde yazılması gerekenler (ör. bildirim outbox'ı) — işlem geri
 * alınırsa onlar da geri alınır.</li>
 * <li>AFTER_COMMIT: değişiklik kesinleştikten sonra başlayacak yeni işler (ör. sıradakine teklif).</li>
 * </ul>
 */
public final class BookingEvents {

	private BookingEvents() {
	}

	/** Rezervasyon onaylandı (müşteri onayı, ödeme bildirimi veya personel kaydı). */
	public record ReservationConfirmed(Long reservationId) {
	}

	/**
	 * Bir rezervasyonun tuttuğu aralık boşaldı (iptal, süre dolumu, taşıma). Bekleme listesindeki
	 * sıradaki kişiye teklif için kullanılır.
	 */
	public record SlotReleased(Long reservationId, Long pitchId, Instant start, Instant end) {
	}

	/** Düzenli rezervasyon serisi oluşturuldu (her maç için ayrı bildirim yerine tek bildirim). */
	public record SeriesCreated(Long seriesId, int count) {
	}

	/** Bekleme listesindeki müşteriye boşalan saat için teklif açıldı. */
	public record WaitlistOffered(Long entryId, Long reservationId) {
	}

}
