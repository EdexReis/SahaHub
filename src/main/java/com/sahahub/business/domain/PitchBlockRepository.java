package com.sahahub.business.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PitchBlockRepository extends JpaRepository<PitchBlock, Long> {

	@Query("""
			select b from PitchBlock b
			where b.pitchId in :pitchIds and b.cancelled = false
			  and b.startsAt < :to and b.endsAt > :from
			order by b.startsAt""")
	List<PitchBlock> findActiveOverlapping(Collection<Long> pitchIds, Instant from, Instant to);

}
