package com.sahahub.community.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

	@Query("select m from TeamMember m where m.teamId = :teamId and m.leftAt is null order by m.role, m.joinedAt")
	List<TeamMember> activeMembers(Long teamId);

	@Query("select m from TeamMember m where m.teamId = :teamId and m.userId = :userId and m.leftAt is null")
	Optional<TeamMember> activeMembership(Long teamId, Long userId);

	@Query("select count(m) from TeamMember m where m.teamId = :teamId and m.leftAt is null")
	long activeCount(Long teamId);

}
