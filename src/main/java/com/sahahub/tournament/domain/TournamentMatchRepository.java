package com.sahahub.tournament.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, Long> {

	List<TournamentMatch> findByTournamentIdOrderByRoundAscIdAsc(Long tournamentId);

	@Query("select count(m) from TournamentMatch m where m.tournamentId = :tournamentId and m.status <> 'PLAYED'")
	long unplayedCount(Long tournamentId);

	/** Personel takviminde gösterilecek planlı maçlar. */
	@Query("""
			select m from TournamentMatch m where m.pitchId in :pitchIds and m.status <> 'UNSCHEDULED'
			  and m.startsAt < :to and m.endsAt > :from""")
	List<TournamentMatch> scheduledOverlapping(Collection<Long> pitchIds, Instant from, Instant to);

}
