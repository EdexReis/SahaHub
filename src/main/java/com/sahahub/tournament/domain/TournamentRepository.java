package com.sahahub.tournament.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

	/** Takım ekleme ve fikstür oluşturma lig satırı kilitlenerek yapılır (iki kez fikstür üretilmez). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Tournament t where t.id = :id")
	Optional<Tournament> findForUpdate(Long id);

	List<Tournament> findByBranchIdOrderByCreatedAtDesc(Long branchId);

	/** Herkese açık ligler: taslak olmayan, işletmesi aktif, şubesi arşivlenmemiş. */
	@Query("""
			select t from Tournament t where t.status <> 'DRAFT'
			  and t.businessId in (select b.id from Business b where b.status = 'ACTIVE')
			  and t.branchId in (select br.id from Branch br where br.archived = false)
			order by t.status, t.startedAt desc""")
	List<Tournament> publicTournaments();

}
