package com.sahahub.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByIdempotencyKey(String key);

	Optional<Payment> findByProviderRef(String providerRef);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.providerRef = :ref")
	Optional<Payment> findByProviderRefForUpdate(String ref);

	List<Payment> findByReservationIdOrderByIdAsc(Long reservationId);

	List<Payment> findByReservationIdInOrderByIdAsc(Collection<Long> reservationIds);

	boolean existsByRelatedPaymentIdAndKind(Long relatedPaymentId, Payment.Kind kind);

	List<Payment> findByRelatedPaymentIdOrderByIdAsc(Long relatedPaymentId);

	/** Şubede onay bekleyen havale bildirimleri. */
	@Query("""
			select p from Payment p where p.branchId = :branchId and p.method = 'BANK_TRANSFER'
			  and p.kind = 'CHARGE' and p.status = 'PENDING' order by p.createdAt""")
	List<Payment> findPendingTransfers(Long branchId);

	/** Şubede başarısız olmuş, henüz başarılı bir tekrarı olmayan iadeler (personel takibi gerekir). */
	@Query("""
			select p from Payment p where p.branchId = :branchId and p.kind = 'REFUND' and p.status = 'FAILED'
			  and not exists (select 1 from Payment q where q.relatedPaymentId = p.relatedPaymentId
			                  and q.kind = 'REFUND' and q.status = 'SUCCEEDED' and q.id > p.id)
			order by p.createdAt""")
	List<Payment> findUnresolvedFailedRefunds(Long branchId);

	/** Kasa oturumundaki nakit hareketlerin net etkisi (tahsilat +, iade/ters kayıt −). */
	@Query("""
			select coalesce(sum(case when p.kind = 'CHARGE' then p.amount else -p.amount end), 0)
			from Payment p where p.cashSessionId = :sessionId and p.method = 'CASH' and p.status = 'SUCCEEDED'""")
	BigDecimal netCashInSession(Long sessionId);

	@Query("""
			select p from Payment p where p.branchId = :branchId and p.status = 'SUCCEEDED'
			  and p.completedAt >= :from and p.completedAt < :to order by p.completedAt""")
	List<Payment> findCompletedBetween(Long branchId, Instant from, Instant to);

}
