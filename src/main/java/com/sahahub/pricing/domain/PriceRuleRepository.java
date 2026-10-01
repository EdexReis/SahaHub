package com.sahahub.pricing.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRuleRepository extends JpaRepository<PriceRule, Long> {

	List<PriceRule> findByPitchIdAndActiveTrue(Long pitchId);

	List<PriceRule> findByPitchIdInAndActiveTrue(Collection<Long> pitchIds);

}
