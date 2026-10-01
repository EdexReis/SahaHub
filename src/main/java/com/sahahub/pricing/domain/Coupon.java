package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * İşletme genelinde geçerli indirim kuponu. Kullanım sayacı, uygulamada değil veritabanında
 * koşullu UPDATE ile artırılır (CouponRepository.tryUse); eşzamanlı iki kullanım sınırı aşamaz.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

	public enum Kind {

		PERCENT, FIXED

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "business_id", nullable = false)
	private Long businessId;

	@Column(nullable = false)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Kind kind;

	@Column(nullable = false)
	private BigDecimal value;

	@Column(name = "valid_from")
	private LocalDate validFrom;

	@Column(name = "valid_to")
	private LocalDate validTo;

	@Column(name = "max_uses", nullable = false)
	private int maxUses;

	@Column(name = "used_count", nullable = false)
	private int usedCount;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Coupon() {
	}

	public Coupon(Long businessId, String code, Kind kind, BigDecimal value, int maxUses, LocalDate validFrom,
			LocalDate validTo, Instant createdAt) {
		this.businessId = businessId;
		this.code = normalize(code);
		this.kind = kind;
		this.value = value;
		this.maxUses = maxUses;
		this.validFrom = validFrom;
		this.validTo = validTo;
		this.createdAt = createdAt;
	}

	/** Kodlar büyük harfle saklanır. Locale.ROOT: Türkçe varsayılanda "i" → "İ" olmasın. */
	public static String normalize(String code) {
		return code.strip().toUpperCase(Locale.ROOT);
	}

	/** Kupon verilen günde (şube yerel tarihi) geçerli mi? Kullanım sınırı ayrıca kontrol edilir. */
	public boolean validOn(LocalDate day) {
		return active && (validFrom == null || !day.isBefore(validFrom)) && (validTo == null || !day.isAfter(validTo));
	}

	public void deactivate() {
		this.active = false;
	}

	public Long getId() {
		return id;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public String getCode() {
		return code;
	}

	public Kind getKind() {
		return kind;
	}

	public BigDecimal getValue() {
		return value;
	}

	public LocalDate getValidFrom() {
		return validFrom;
	}

	public LocalDate getValidTo() {
		return validTo;
	}

	public int getMaxUses() {
		return maxUses;
	}

	public int getUsedCount() {
		return usedCount;
	}

	public boolean isActive() {
		return active;
	}

}
