package com.sahahub.payment.domain;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

	List<Expense> findTop30ByBranchIdOrderByCreatedAtDesc(Long branchId);

	boolean existsByReversalOf(Long expenseId);

	/** Kasa oturumundan ödenen giderlerin toplamı (ters kayıtlar negatif olduğu için düşer). */
	@Query("select coalesce(sum(e.amount), 0) from Expense e where e.cashSessionId = :sessionId and e.paidFromCash = true")
	BigDecimal cashExpensesInSession(Long sessionId);

}
