package com.sahahub.booking.app;

import java.util.Collection;
import java.util.Map;

import com.sahahub.booking.domain.Reservation;

/**
 * Rezervasyon modülünün ödeme bilgisine ihtiyaç duyduğu iki yer için arayüz:
 * takvimdeki ödeme etiketi ve kapora ödenmeden onayın engellenmesi.
 * <p>
 * Bağımlılığın yönü: ödeme modülü rezervasyon modülünü kullanır, tersi değil. Rezervasyon modülü
 * yalnızca bu arayüzü tanımlar; uygulamasını ödeme modülü verir (Dependency Inversion).
 */
public interface PaymentStatusPort {

	record Badge(String state, String label) {
	}

	/** Etiket gösterilecek rezervasyonlar için id → etiket. Etiketi olmayanlar haritada yer almaz. */
	Map<Long, Badge> badges(Collection<Reservation> reservations);

	/** Kapora kuralı varsa ödenen tutar kaporayı karşılıyor mu? Kural yoksa true. */
	boolean depositCovered(Reservation reservation);

}
