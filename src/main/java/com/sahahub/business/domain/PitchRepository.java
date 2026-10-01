package com.sahahub.business.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchRepository extends JpaRepository<Pitch, Long> {

	List<Pitch> findByBranchIdAndActiveTrueOrderByName(Long branchId);

}
