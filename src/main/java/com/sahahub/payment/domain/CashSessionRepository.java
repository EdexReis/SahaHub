package com.sahahub.payment.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface CashSessionRepository extends JpaRepository<CashSession, Long> {

	@Query("select s from CashSession s where s.branchId = :branchId and s.closedAt is null")
	Optional<CashSession> findOpen(Long branchId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from CashSession s where s.branchId = :branchId and s.closedAt is null")
	Optional<CashSession> findOpenForUpdate(Long branchId);

	List<CashSession> findTop10ByBranchIdOrderByOpenedAtDesc(Long branchId);

}
