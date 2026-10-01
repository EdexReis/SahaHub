package com.sahahub.tournament.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TournamentEntryRepository extends JpaRepository<TournamentEntry, Long> {

	List<TournamentEntry> findByTournamentIdOrderById(Long tournamentId);

	long countByTournamentId(Long tournamentId);

	Optional<TournamentEntry> findByLinkCode(String linkCode);

	/** Kilitlemeden önce turnuvayı bulmak için (varlık yüklenmeden). */
	@Query("select e.tournamentId from TournamentEntry e where e.linkCode = :code")
	Optional<Long> tournamentIdOfLinkCode(String code);

	/** Bir platform takımının bağlı olduğu kayıtlar (en yeni önce). */
	List<TournamentEntry> findByTeamIdOrderByIdDesc(Long teamId);

}
