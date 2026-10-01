package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;

/**
 * Süresi dolan geçici tutmaları serbest bırakır.
 * <p>
 * Tekrar çalıştırılabilir (idempotent): yalnızca HELD ve süresi geçmiş kayıtları seçer;
 * işlenen kayıt EXPIRED olduğu için ikinci çalıştırmada tekrar seçilmez.
 * Aynı anda iki kopya çalışsa bile FOR UPDATE SKIP LOCKED ile aynı kaydı iki kez işlemez.
 */
@Service
public class HoldExpiryService {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);
	static final int BATCH_SIZE = 100;

	private final ReservationRepository reservations;
	private final OccupancyService occupancy;
	private final ReservationPricingService pricing;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public HoldExpiryService(ReservationRepository reservations, OccupancyService occupancy,
			ReservationPricingService pricing, ApplicationEventPublisher events, Clock clock) {
		this.reservations = reservations;
		this.occupancy = occupancy;
		this.pricing = pricing;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Süresi dolmuş tek bir tutmayı kapatır: durum EXPIRED, saha serbest, kupon hakkı geri.
	 * Zamanlanmış görev, geç onay denemesi ve geç gelen ödeme bildirimi aynı yolu kullanır.
	 * Çağıranın transaction'ında ve rezervasyon satırı kilitliyken çağrılmalıdır.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void expire(Reservation r, Instant now) {
		r.expire(now);
		occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
		pricing.releaseCoupons(r.getId());
		events.publishEvent(new BookingEvents.SlotReleased(r.getId(), r.getPitchId(), r.getStartsAt(),
				r.occupiedRange().end()));
	}

	/** Bir grup süresi dolmuş tutmayı işler; işlenen kayıt sayısını döner. */
	@Transactional
	public int expireDueHolds() {
		Instant now = Instant.now(clock);
		List<Long> ids = reservations.lockDueHolds(now, BATCH_SIZE);
		for (Long id : ids) {
			expire(reservations.findById(id).orElseThrow(), now);
		}
		if (!ids.isEmpty()) {
			log.info("Süresi dolan {} geçici tutma serbest bırakıldı", ids.size());
		}
		return ids.size();
	}

}
