package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Şubenin sunduğu ek hizmet: ekipman kiralama, hakem, içecek paketi gibi. */
@Entity
@Table(name = "extra_service")
public class ExtraService {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(nullable = false)
	private String name;

	@Column(name = "unit_price", nullable = false)
	private BigDecimal unitPrice;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ExtraService() {
	}

	public ExtraService(Long branchId, String name, BigDecimal unitPrice, Instant createdAt) {
		this.branchId = branchId;
		this.name = name.strip();
		this.unitPrice = unitPrice;
		this.createdAt = createdAt;
	}

	public void deactivate() {
		this.active = false;
	}

	public Long getId() {
		return id;
	}

	public Long getBranchId() {
		return branchId;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public boolean isActive() {
		return active;
	}

}
