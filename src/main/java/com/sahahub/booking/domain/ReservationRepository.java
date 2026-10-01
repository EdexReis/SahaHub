package com.sahahub.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByCode(String code);

	/**
	 * Durum değiştirmeden önce satırı kilitler (SELECT ... FOR UPDATE). Aynı rezervasyonu
	 * aynı anda onaylamaya ve iptal etmeye çalışan iki istek sırayla işlenir.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Reservation r where r.code = :code")
	Optional<Reservation> findByCodeForUpdate(String code);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Reservation r where r.id = :id")
	Optional<Reservation> findByIdForUpdate(Long id);

	/**
	 * Süresi dolmuş tutmaların id'leri. SKIP LOCKED: aynı anda çalışan iki temizlik görevi
	 * aynı satırı beklemez, her biri farklı satırları işler.
	 */
	@Query(value = """
			select id from reservation
			where status = 'HELD' and hold_expires_at <= :now
			order by hold_expires_at
			limit :limit
			for update skip locked""", nativeQuery = true)
	List<Long> lockDueHolds(Instant now, int limit);

	@Query("""
			select r from Reservation r
			where r.branchId = :branchId and r.startsAt < :to and r.endsAt > :from
			  and r.status in :statuses
			order by r.startsAt""")
	List<Reservation> findForCalendar(Long branchId, Instant from, Instant to, Collection<ReservationStatus> statuses);

	/** Müşterinin henüz bitmemiş, açık rezervasyonları: en yakın maç önce. */
	@Query("""
			select r from Reservation r
			where r.customerId = :customerId and r.endsAt > :now and r.status in :open
			order by r.startsAt""")
	List<Reservation> findUpcoming(Long customerId, Instant now, Collection<ReservationStatus> open, Pageable limit);

	/** Geçmiş, iptal edilmiş veya süresi dolmuş rezervasyonlar: en yeni önce, sayfalı. */
	@Query(value = """
			select r from Reservation r
			where r.customerId = :customerId and (r.endsAt <= :now or r.status not in :open)
			order by r.startsAt desc""", countQuery = """
			select count(r) from Reservation r
			where r.customerId = :customerId and (r.endsAt <= :now or r.status not in :open)""")
	Page<Reservation> findHistory(Long customerId, Instant now, Collection<ReservationStatus> open, Pageable pageable);

	long countByCustomerIdAndStatus(Long customerId, ReservationStatus status);

	/** Serinin verilen andan sonra başlayan açık maçları (kilitli). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select r from Reservation r where r.seriesId = :seriesId and r.startsAt >= :from
			  and r.status in ('HELD', 'CONFIRMED') order by r.startsAt""")
	List<Reservation> openInSeriesFromForUpdate(Long seriesId, Instant from);

	List<Reservation> findBySeriesIdOrderBySeriesIndex(Long seriesId);

	/** Hatırlatma görevi: başlangıcı verilen aralıkta olan onaylı rezervasyonlar. */
	@Query("""
			select r from Reservation r where r.status = 'CONFIRMED' and r.startsAt > :from and r.startsAt <= :to
			order by r.startsAt""")
	List<Reservation> confirmedStartingBetween(Instant from, Instant to);

}
