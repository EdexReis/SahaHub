package com.sahahub.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Basit işletme gideri. Hatalı gider silinmez: aynı tutarın negatifiyle ters kaydı girilir
 * (reversalOf ile bağlı, her gider en fazla bir kez).
 */
@Entity
@Table(name = "expense")
public class Expense {

	public enum Category {

		MAINTENANCE("Bakım/onarım"), UTILITIES("Elektrik/su/doğalgaz"), SUPPLIES("Malzeme"), STAFF("Personel"),
		OTHER("Diğer");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "business_id", nullable = false, updatable = false)
	private Long businessId;

	@Column(name = "branch_id", nullable = false, updatable = false)
	private Long branchId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Category category;

	@Column(nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, updatable = false)
	private String description;

	@Column(name = "paid_from_cash", nullable = false, updatable = false)
	private boolean paidFromCash;

	@Column(name = "cash_session_id", updatable = false)
	private Long cashSessionId;

	@Column(name = "reversal_of", updatable = false)
	private Long reversalOf;

	@Column(name = "recorded_by", nullable = false, updatable = false)
	private Long recordedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Expense() {
	}

	public Expense(Long businessId, Long branchId, Category category, BigDecimal amount, String description,
			Long cashSessionId, Long recordedBy, Instant now) {
		if (amount == null || amount.signum() <= 0) {
			throw new BusinessRuleException("Gider tutarı sıfırdan büyük olmalı.");
		}
		if (description == null || description.isBlank()) {
			throw new BusinessRuleException("Gider açıklamasını yazın.");
		}
		this.businessId = businessId;
		this.branchId = branchId;
		this.category = category;
		this.amount = amount;
		this.description = description.strip();
		this.paidFromCash = cashSessionId != null;
		this.cashSessionId = cashSessionId;
		this.recordedBy = recordedBy;
		this.createdAt = now;
	}

	/** Bu giderin ters kaydı. Kasadan ödendiyse, ters kayıt da (şu an açık) kasaya işlenir. */
	public Expense reversal(Long openCashSessionId, Long recordedBy, Instant now) {
		if (reversalOf != null) {
			throw new BusinessRuleException("Ters kayıt yeniden ters çevrilemez.");
		}
		Expense r = new Expense();
		r.businessId = businessId;
		r.branchId = branchId;
		r.category = category;
		r.amount = amount.negate();
		r.description = "Ters kayıt: " + description;
		r.paidFromCash = paidFromCash;
		r.cashSessionId = paidFromCash ? openCashSessionId : null;
		r.reversalOf = id;
		r.recordedBy = recordedBy;
		r.createdAt = now;
		return r;
	}

	public Long getId() {
		return id;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Category getCategory() {
		return category;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getDescription() {
		return description;
	}

	public boolean isPaidFromCash() {
		return paidFromCash;
	}

	public Long getCashSessionId() {
		return cashSessionId;
	}

	public Long getReversalOf() {
		return reversalOf;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
