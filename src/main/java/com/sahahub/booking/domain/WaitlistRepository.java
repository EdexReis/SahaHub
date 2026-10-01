package com.sahahub.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

	/** Boşluk için sıradaki kişiler (en eski önce), kilitli: iki işlem aynı kişiye iki teklif açamaz. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select w from WaitlistEntry w
			where w.pitchId = :pitchId and w.startsAt = :startsAt and w.status = 'WAITING'
			order by w.createdAt, w.id""")
	List<WaitlistEntry> queueForUpdate(Long pitchId, Instant startsAt);

	/** Boşalan aralıkla kesişen, sırası olan başlangıç saatleri. */
	@Query("""
			select distinct w.startsAt from WaitlistEntry w where w.pitchId = :pitchId and w.status = 'WAITING'
			  and w.startsAt < :end and w.endsAt > :start order by w.startsAt""")
	List<Instant> waitingStartsOverlapping(Long pitchId, Instant start, Instant end);

	Optional<WaitlistEntry> findByOfferReservationIdAndStatus(Long reservationId, WaitlistEntry.Status status);

	@Query("""
			select w from WaitlistEntry w where w.customerId = :customerId and w.status in ('WAITING', 'OFFERED')
			  and w.endsAt > :now order by w.startsAt""")
	List<WaitlistEntry> activeForCustomer(Long customerId, Instant now);

	@Query("""
			select count(w) from WaitlistEntry w where w.pitchId = :pitchId and w.startsAt = :startsAt
			  and w.status = 'WAITING' and (w.createdAt < :createdAt or (w.createdAt = :createdAt and w.id < :id))""")
	long aheadOf(Long pitchId, Instant startsAt, Instant createdAt, Long id);

	@Query("""
			select count(w) from WaitlistEntry w where w.pitchId = :pitchId and w.startsAt = :startsAt
			  and w.status = 'WAITING'""")
	long waitingCount(Long pitchId, Instant startsAt);

}
