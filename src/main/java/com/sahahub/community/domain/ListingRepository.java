package com.sahahub.community.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ListingRepository extends JpaRepository<Listing, Long> {

	/** Başvuru kabulünde ilan kilitlenir: kontenjan eşzamanlı kabullerde aşılmaz. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Listing l where l.id = :id")
	Optional<Listing> findForUpdate(Long id);

	/** Herkese açık liste: açık, süresi dolmamış; şehir ve tür isteğe bağlı süzgeç. */
	@Query("""
			select l from Listing l where l.status = 'OPEN' and l.expiresAt > :now
			  and (:city is null or l.city = :city) and (:kind is null or l.kind = :kind)
			  and l.teamId in (select t.id from Team t where t.disbandedAt is null)
			order by coalesce(l.playAt, l.expiresAt), l.id""")
	List<Listing> openListings(Instant now, String city, Listing.Kind kind, Pageable page);

	@Query("select distinct l.city from Listing l where l.status = 'OPEN' and l.expiresAt > :now order by l.city")
	List<String> openCities(Instant now);

	List<Listing> findByAuthorIdOrderByCreatedAtDescIdDesc(Long authorId, Pageable page);

	@Query("select count(l) from Listing l where l.authorId = :authorId and l.status = 'OPEN' and l.expiresAt > :now")
	long openCountByAuthor(Long authorId, Instant now);

	@Query("select l from Listing l where l.reservationId = :reservationId and l.status = 'OPEN'")
	List<Listing> openForReservation(Long reservationId);

	@Query("select l from Listing l where l.teamId = :teamId and l.status = 'OPEN'")
	List<Listing> openForTeam(Long teamId);

}
