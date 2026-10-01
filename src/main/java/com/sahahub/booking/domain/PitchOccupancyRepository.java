package com.sahahub.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PitchOccupancyRepository extends JpaRepository<PitchOccupancy, Long> {

	/** Verilen sahalarda [from, to) ile kesişen aktif doluluklar (uygunluk ekranı için). */
	@Query("""
			select o from PitchOccupancy o
			where o.pitchId in :pitchIds and o.active = true
			  and o.startsAt < :to and o.endsAt > :from
			order by o.startsAt""")
	List<PitchOccupancy> findActiveOverlapping(Collection<Long> pitchIds, Instant from, Instant to);

	/**
	 * Kaynağın doluluğunu kapatır (iptal, süre dolması, taşıma). Satır silinmez:
	 * geçmiş korunur, yalnızca kısıtın dışına çıkar.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("""
			update PitchOccupancy o set o.active = false
			where o.sourceType = :source and o.sourceId = :sourceId and o.active = true""")
	int deactivate(PitchOccupancy.Source source, Long sourceId);

}
