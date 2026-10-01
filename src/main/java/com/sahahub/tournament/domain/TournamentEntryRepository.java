package com.sahahub.tournament.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentEntryRepository extends JpaRepository<TournamentEntry, Long> {

	List<TournamentEntry> findByTournamentIdOrderById(Long tournamentId);

	long countByTournamentId(Long tournamentId);

}
