package com.sahahub.business.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchOpeningHoursRepository extends JpaRepository<BranchOpeningHours, BranchOpeningHours.Key> {

	List<BranchOpeningHours> findByBranchId(Long branchId);

}
