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
 * Bir para hareketi. Kurallar:
 * <ul>
 * <li>Tutar her zaman pozitiftir; yönü {@link Kind} belirler.</li>
 * <li>Hareket silinmez, tutarı değiştirilmez. Hatalı tahsilat REVERSAL ile, müşteriye geri ödeme
 * REFUND ile kaydedilir.</li>
 * <li>Durum yalnızca bir kez PENDING'den SUCCEEDED veya FAILED'a geçer.</li>
 * <li>idempotencyKey veritabanında tekildir: aynı istek iki kez gelse de tek hareket oluşur.</li>
 * </ul>
 */
@Entity
@Table(name = "payment")
public class Payment {

	public enum Kind {

		CHARGE("Tahsilat"), REFUND("İade"), REVERSAL("Ters kayıt");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public enum Method {

		CASH("Nakit"),
		/** Personelin elle girdiği POS slip tutarı. Gerçek bir POS cihazı entegrasyonu DEĞİLDİR. */
		MANUAL_POS("Manuel POS kaydı"),
		BANK_TRANSFER("Havale/EFT"),
		/** Simülasyon sağlayıcısı üzerinden çevrim içi ödeme. Gerçek para çekilmez. */
		ONLINE_SIM("Çevrim içi (simülasyon)");

		private final String label;

		Method(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public enum Status {

		PENDING("Bekliyor"), SUCCEEDED("Başarılı"), FAILED("Başarısız");

		private final String label;

		Status(String label) {
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

	@Column(name = "reservation_id", nullable = false, updatable = false)
	private Long reservationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Kind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Method method;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, updatable = false)
	private String currency;

	@Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
	private String idempotencyKey;

	@Column(name = "related_payment_id", updatable = false)
	private Long relatedPaymentId;

	@Column(name = "provider_ref")
	private String providerRef;

	@Column(name = "cash_session_id", updatable = false)
	private Long cashSessionId;

	@Column(name = "payer_name", updatable = false)
	private String payerName;

	@Column(updatable = false)
	private String note;

	@Column(name = "failure_reason")
	private String failureReason;

	@Column(name = "recorded_by", updatable = false)
	private Long recordedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected Payment() {
	}

	private Payment(Long businessId, Long branchId, Long reservationId, Kind kind, Method method, BigDecimal amount,
			String currency, String idempotencyKey, Long recordedBy, Instant now) {
		if (amount == null || amount.signum() <= 0) {
			throw new BusinessRuleException("Tutar sıfırdan büyük olmalı.");
		}
		this.businessId = businessId;
		this.branchId = branchId;
		this.reservationId = reservationId;
		this.kind = kind;
		this.method = method;
		this.amount = amount;
		this.currency = currency;
		this.idempotencyKey = idempotencyKey;
		this.recordedBy = recordedBy;
		this.createdAt = now;
		this.status = Status.PENDING;
	}

	/** Personelin kasada/POS'ta aldığı ödeme: anında başarılı. */
	public static Payment collected(Long businessId, Long branchId, Long reservationId, Method method,
			BigDecimal amount, String currency, String key, Long cashSessionId, String note, Long staffId,
			Instant now) {
		Payment p = new Payment(businessId, branchId, reservationId, Kind.CHARGE, method, amount, currency, key,
				staffId, now);
		p.cashSessionId = cashSessionId;
		p.note = note;
		p.succeed(now);
		return p;
	}

	/** Sonucu sonradan belli olacak tahsilat: havale bildirimi veya çevrim içi ödeme. */
	public static Payment pendingCharge(Long businessId, Long branchId, Long reservationId, Method method,
			BigDecimal amount, String currency, String key, String payerName, String note, Long userId, Instant now) {
		Payment p = new Payment(businessId, branchId, reservationId, Kind.CHARGE, method, amount, currency, key,
				userId, now);
		p.payerName = payerName;
		p.note = note;
		return p;
	}

	/** İade (sonucu sağlayıcıya bağlı olabilir) veya ters kayıt. */
	public static Payment against(Payment original, Kind kind, Method method, BigDecimal amount, String key,
			Long cashSessionId, String note, Long userId, Instant now) {
		if (original.kind != Kind.CHARGE) {
			throw new IllegalArgumentException("İade/ters kayıt yalnızca tahsilata bağlanır");
		}
		Payment p = new Payment(original.businessId, original.branchId, original.reservationId, kind, method, amount,
				original.currency, key, userId, now);
		p.relatedPaymentId = original.id;
		p.cashSessionId = cashSessionId;
		p.note = note;
		return p;
	}

	public void attachProviderRef(String providerRef) {
		this.providerRef = providerRef;
	}

	public void succeed(Instant now) {
		requirePending();
		this.status = Status.SUCCEEDED;
		this.completedAt = now;
	}

	public void fail(String reason, Instant now) {
		requirePending();
		this.status = Status.FAILED;
		this.failureReason = reason;
		this.completedAt = now;
	}

	private void requirePending() {
		if (status != Status.PENDING) {
			throw new IllegalStateException("Sonuçlanmış ödeme hareketi değiştirilemez: " + id);
		}
	}

	public boolean isPending() {
		return status == Status.PENDING;
	}

	public boolean isSucceeded() {
		return status == Status.SUCCEEDED;
	}

	/** Ödenen tutara etkisi: başarılı tahsilat +, başarılı iade/ters kayıt −, diğerleri 0. */
	public BigDecimal signedAmount() {
		if (status != Status.SUCCEEDED) {
			return BigDecimal.ZERO;
		}
		return kind == Kind.CHARGE ? amount : amount.negate();
	}

	public Long getId() {
		return id;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public Kind getKind() {
		return kind;
	}

	public Method getMethod() {
		return method;
	}

	public Status getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public Long getRelatedPaymentId() {
		return relatedPaymentId;
	}

	public String getProviderRef() {
		return providerRef;
	}

	public Long getCashSessionId() {
		return cashSessionId;
	}

	public String getPayerName() {
		return payerName;
	}

	public String getNote() {
		return note;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

}
