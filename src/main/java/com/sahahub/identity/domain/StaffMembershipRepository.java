package com.sahahub.identity.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffMembershipRepository extends JpaRepository<StaffMembership, Long> {

	List<StaffMembership> findByUserIdAndActiveTrue(Long userId);

	List<StaffMembership> findByUserIdAndBusinessIdAndActiveTrue(Long userId, Long businessId);

}
