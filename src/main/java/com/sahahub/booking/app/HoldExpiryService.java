package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private final Clock clock;

	public HoldExpiryService(ReservationRepository reservations, OccupancyService occupancy, Clock clock) {
		this.reservations = reservations;
		this.occupancy = occupancy;
		this.clock = clock;
	}

	/** Bir grup süresi dolmuş tutmayı işler; işlenen kayıt sayısını döner. */
	@Transactional
	public int expireDueHolds() {
		Instant now = Instant.now(clock);
		List<Long> ids = reservations.lockDueHolds(now, BATCH_SIZE);
		for (Long id : ids) {
			Reservation r = reservations.findById(id).orElseThrow();
			r.expire(now);
			occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
		}
		if (!ids.isEmpty()) {
			log.info("Süresi dolan {} geçici tutma serbest bırakıldı", ids.size());
		}
		return ids.size();
	}

}
