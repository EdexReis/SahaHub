package com.sahahub.booking.app;

import java.util.Collection;
import java.util.List;

import com.sahahub.shared.domain.TimeRange;

/**
 * Personel takviminde rezervasyon dışı maçları (lig) göstermek için. Rezervasyon modülü lig modülünü
 * tanımaz; uygulamasını lig modülü verir (bağımlılık yönü tournament → booking kalır).
 */
public interface MatchCalendarPort {

	/** @param link takvimde tıklanınca açılacak sayfa */
	record MatchSlot(Long pitchId, TimeRange play, String title, String subtitle, String link) {
	}

	List<MatchSlot> matches(Collection<Long> pitchIds, TimeRange range);

}
