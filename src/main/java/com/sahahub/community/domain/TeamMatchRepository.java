package com.sahahub.community.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamMatchRepository extends JpaRepository<TeamMatch, Long> {

	/** Yaklaşan (planlı) maçlar, en yakını önce. */
	@Query("""
			select m from TeamMatch m where m.teamId in :teamIds and m.status = 'SCHEDULED' and m.startsAt > :now
			order by m.startsAt, m.id""")
	List<TeamMatch> upcoming(Collection<Long> teamIds, Instant now);

	/** Geçmiş: başlamış veya oynanmış maçlar (iptaller hariç), en yenisi önce. */
	@Query("""
			select m from TeamMatch m where m.teamId = :teamId and m.status <> 'CANCELLED' and m.startsAt <= :now
			order by m.startsAt desc, m.id desc""")
	List<TeamMatch> history(Long teamId, Instant now, Pageable page);

	/** Hatırlatma adayları: [from, to) aralığında başlayan planlı maçlar. */
	@Query("""
			select m from TeamMatch m where m.status = 'SCHEDULED' and m.startsAt > :from and m.startsAt <= :to
			order by m.startsAt, m.id""")
	List<TeamMatch> scheduledStartingBetween(Instant from, Instant to);

	/** Bir lig maçının takımlardaki iptal edilmemiş kopyaları. */
	@Query("select m from TeamMatch m where m.tournamentMatchId = :tournamentMatchId and m.status <> 'CANCELLED'")
	List<TeamMatch> liveForTournamentMatch(Long tournamentMatchId);

	@Query("select m from TeamMatch m where m.reservationId = :reservationId and m.status = 'SCHEDULED'")
	List<TeamMatch> scheduledForReservation(Long reservationId);

}
