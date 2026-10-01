package com.sahahub.pricing.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtraServiceRepository extends JpaRepository<ExtraService, Long> {

	List<ExtraService> findByBranchIdAndActiveTrueOrderByName(Long branchId);

}
