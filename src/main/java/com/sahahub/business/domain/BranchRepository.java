package com.sahahub.business.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

	List<Branch> findByBusinessIdAndArchivedFalseOrderByName(Long businessId);

	List<Branch> findByIdInAndArchivedFalseOrderByName(Collection<Long> ids);

}
