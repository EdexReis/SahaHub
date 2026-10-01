package com.sahahub.business.domain;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchSpecialDayRepository extends JpaRepository<BranchSpecialDay, Long> {

	List<BranchSpecialDay> findByBranchIdAndDayBetween(Long branchId, LocalDate from, LocalDate to);

}
