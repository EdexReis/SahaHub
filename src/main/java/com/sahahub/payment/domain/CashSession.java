package com.sahahub.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Günlük kasa. Açılışta kasadaki başlangıç parası (opening float) girilir; kapanışta sayılan tutar
 * girilir ve sistemin beklediği tutarla farkı kaydedilir.
 * <p>
 * Beklenen = başlangıç parası + nakit tahsilatlar − nakit iadeler − nakit ters kayıtlar − kasadan giderler.
 * Şube başına aynı anda tek açık kasa olabilir (kısmi tekil indeks).
 */
@Entity
@Table(name = "cash_session")
public class CashSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "branch_id", nullable = false, updatable = false)
	private Long branchId;

	@Column(name = "opened_by", nullable = false, updatable = false)
	private Long openedBy;

	@Column(name = "opened_at", nullable = false, updatable = false)
	private Instant openedAt;

	@Column(name = "opening_float", nullable = false, updatable = false)
	private BigDecimal openingFloat;

	@Column(name = "closed_by")
	private Long closedBy;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "expected_amount")
	private BigDecimal expectedAmount;

	@Column(name = "counted_amount")
	private BigDecimal countedAmount;

	private String note;

	protected CashSession() {
	}

	public CashSession(Long branchId, BigDecimal openingFloat, Long openedBy, Instant now) {
		if (openingFloat == null || openingFloat.signum() < 0) {
			throw new BusinessRuleException("Açılış tutarı negatif olamaz.");
		}
		this.branchId = branchId;
		this.openingFloat = openingFloat;
		this.openedBy = openedBy;
		this.openedAt = now;
	}

	public void close(BigDecimal expected, BigDecimal counted, String note, Long closedBy, Instant now) {
		if (closedAt != null) {
			throw new BusinessRuleException("Kasa zaten kapatılmış.");
		}
		if (counted == null || counted.signum() < 0) {
			throw new BusinessRuleException("Sayılan tutarı girin.");
		}
		this.expectedAmount = expected;
		this.countedAmount = counted;
		this.note = note == null || note.isBlank() ? null : note.strip();
		this.closedBy = closedBy;
		this.closedAt = now;
	}

	public boolean isOpen() {
		return closedAt == null;
	}

	/** Sayılan − beklenen: pozitif kasa fazlası, negatif kasa açığı. */
	public BigDecimal difference() {
		return countedAmount == null ? null : countedAmount.subtract(expectedAmount);
	}

	public Long getId() {
		return id;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Instant getOpenedAt() {
		return openedAt;
	}

	public BigDecimal getOpeningFloat() {
		return openingFloat;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public BigDecimal getExpectedAmount() {
		return expectedAmount;
	}

	public BigDecimal getCountedAmount() {
		return countedAmount;
	}

	public String getNote() {
		return note;
	}

	public Long getOpenedBy() {
		return openedBy;
	}

	public Long getClosedBy() {
		return closedBy;
	}

}
