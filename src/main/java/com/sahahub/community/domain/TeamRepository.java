package com.sahahub.community.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface TeamRepository extends JpaRepository<Team, Long> {

	Optional<Team> findByInviteCode(String inviteCode);

	/** Üye ekleme/çıkarma sırasında takım satırı kilitlenir: üye sınırı eşzamanlı katılımda aşılmaz. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> findForUpdate(Long id);

	@Query("""
			select t from Team t where t.disbandedAt is null and t.id in (
			  select m.teamId from TeamMember m where m.userId = :userId and m.leftAt is null)
			order by t.name""")
	List<Team> activeTeamsOf(Long userId);

	@Query("""
			select t from Team t where t.disbandedAt is null and t.id in (
			  select m.teamId from TeamMember m where m.userId = :userId and m.leftAt is null and m.role = 'CAPTAIN')
			order by t.name""")
	List<Team> captainedBy(Long userId);

}
