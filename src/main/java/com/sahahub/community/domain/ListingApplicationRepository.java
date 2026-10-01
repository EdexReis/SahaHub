package com.sahahub.community.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ListingApplicationRepository extends JpaRepository<ListingApplication, Long> {

	List<ListingApplication> findByListingIdOrderByCreatedAtAscIdAsc(Long listingId);

	/** Varlığı bağlama yüklemeden ilan numarasını verir (kilit sırası: önce ilan, sonra başvuru). */
	@Query("select a.listingId from ListingApplication a where a.id = :id")
	Optional<Long> listingIdOf(Long id);

	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from ListingApplication a where a.id = :id")
	Optional<ListingApplication> findForUpdate(Long id);

	@Query("select count(a) from ListingApplication a where a.listingId = :listingId and a.status = 'ACCEPTED'")
	long acceptedCount(Long listingId);

	@Query("select a from ListingApplication a where a.listingId = :listingId and a.status = 'PENDING'")
	List<ListingApplication> pending(Long listingId);

	@Query("""
			select a from ListingApplication a where a.listingId = :listingId and a.applicantId = :applicantId
			  and a.status in ('PENDING', 'ACCEPTED')""")
	List<ListingApplication> activeOf(Long listingId, Long applicantId);

	List<ListingApplication> findByApplicantIdOrderByCreatedAtDescIdDesc(Long applicantId, Pageable page);

}
