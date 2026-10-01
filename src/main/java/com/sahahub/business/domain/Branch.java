package com.sahahub.business.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.sahahub.pricing.domain.DepositPolicy;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** İşletmenin bir şubesi. Saat ve iptal kuralları şubenin zaman diliminde değerlendirilir. */
@Entity
@Table(name = "branch")
public class Branch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "business_id", nullable = false)
	private Long businessId;

	@Column(nullable = false)
	private String name;

	private String description;

	private String phone;

	@Column(name = "address_line", nullable = false)
	private String addressLine;

	@Column(nullable = false)
	private String district;

	@Column(nullable = false)
	private String city;

	@Column(name = "time_zone", nullable = false)
	private String timeZone = "Europe/Istanbul";

	@Column(name = "hold_minutes", nullable = false)
	private int holdMinutes = 10;

	/** Müşteri, maç başlangıcından en geç bu kadar saat önce iptal edebilir. */
	@Column(name = "customer_cancel_cutoff_h", nullable = false)
	private int customerCancelCutoffHours = 24;

	/** Müşteri en fazla kaç gün sonrası için rezervasyon yapabilir. */
	@Column(name = "booking_horizon_days", nullable = false)
	private int bookingHorizonDays = 30;

	@Enumerated(EnumType.STRING)
	@Column(name = "deposit_type", nullable = false)
	private DepositPolicy.Type depositType = DepositPolicy.Type.NONE;

	@Column(name = "deposit_value", nullable = false)
	private BigDecimal depositValue = BigDecimal.ZERO;

	/** Havale bildirimi için gösterilen IBAN (isteğe bağlı). */
	@Column(name = "bank_iban")
	private String bankIban;

	@Column(nullable = false)
	private boolean archived;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Branch() {
	}

	public Branch(Long businessId, String name, String addressLine, String district, String city, Instant createdAt) {
		this.businessId = businessId;
		this.name = name;
		this.addressLine = addressLine;
		this.district = district;
		this.city = city;
		this.createdAt = createdAt;
	}

	public void updatePolicies(int holdMinutes, int customerCancelCutoffHours, int bookingHorizonDays) {
		this.holdMinutes = holdMinutes;
		this.customerCancelCutoffHours = customerCancelCutoffHours;
		this.bookingHorizonDays = bookingHorizonDays;
	}

	public void changeDepositPolicy(DepositPolicy policy) {
		this.depositType = policy.type();
		this.depositValue = policy.value();
	}

	public void changeBankIban(String iban) {
		this.bankIban = iban == null || iban.isBlank() ? null : iban.strip();
	}

	public DepositPolicy depositPolicy() {
		return new DepositPolicy(depositType, depositValue);
	}

	public String getBankIban() {
		return bankIban;
	}

	public void describe(String description, String phone) {
		this.description = description;
		this.phone = phone;
	}

	public ZoneId zone() {
		return ZoneId.of(timeZone);
	}

	public Duration customerCancelCutoff() {
		return Duration.ofHours(customerCancelCutoffHours);
	}

	public Long getId() {
		return id;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public String getPhone() {
		return phone;
	}

	public String getAddressLine() {
		return addressLine;
	}

	public String getDistrict() {
		return district;
	}

	public String getCity() {
		return city;
	}

	public String getTimeZone() {
		return timeZone;
	}

	public int getHoldMinutes() {
		return holdMinutes;
	}

	public int getCustomerCancelCutoffHours() {
		return customerCancelCutoffHours;
	}

	public int getBookingHorizonDays() {
		return bookingHorizonDays;
	}

	public boolean isArchived() {
		return archived;
	}

}
