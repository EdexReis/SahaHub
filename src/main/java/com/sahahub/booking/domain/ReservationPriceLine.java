package com.sahahub.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.sahahub.pricing.domain.PriceBreakdown;
import com.sahahub.pricing.domain.PriceQuote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Rezervasyon fiyat kalemi.
 * <ul>
 * <li>PITCH: rezervasyon anındaki saha ücretinin değişmez kopyası. Tarife sonradan değişse de değişmez.</li>
 * <li>EXTRA: ek hizmet (adet × birim fiyat, birim fiyat eklendiği andaki kopya).</li>
 * <li>COUPON / STAFF_DISCOUNT: indirim. Tutarı, PriceBreakdown sırasına göre her değişiklikte
 * yeniden hesaplanır (yüzde indirim ek hizmet eklenince güncellenir).</li>
 * </ul>
 * Kalemler silinmez; kaldırılan ek hizmet/indirim voidedAt ile işaretlenir.
 */
@Entity
@Table(name = "reservation_price_line")
public class ReservationPriceLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reservation_id", nullable = false, updatable = false)
	private Long reservationId;

	@Column(name = "line_no", nullable = false, updatable = false)
	private int lineNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private PriceBreakdown.Kind kind;

	@Column(nullable = false, updatable = false)
	private String label;

	@Column(name = "starts_at", updatable = false)
	private Instant startsAt;

	@Column(name = "ends_at", updatable = false)
	private Instant endsAt;

	@Column(updatable = false)
	private Integer minutes;

	@Column(name = "hourly_rate", updatable = false)
	private BigDecimal hourlyRate;

	@Column(updatable = false)
	private Integer quantity;

	/** EXTRA: birim fiyat; sabit indirim: indirim tutarı (pozitif). */
	@Column(name = "unit_amount", updatable = false)
	private BigDecimal unitAmount;

	/** Yüzde indirim oranı (ör. 10.00). */
	@Column(updatable = false)
	private BigDecimal percent;

	@Column(nullable = false)
	private BigDecimal amount;

	@Column(updatable = false)
	private String reason;

	@Column(name = "price_rule_id", updatable = false)
	private Long priceRuleId;

	@Column(name = "extra_service_id", updatable = false)
	private Long extraServiceId;

	@Column(name = "coupon_id", updatable = false)
	private Long couponId;

	@Column(name = "created_by", updatable = false)
	private Long createdBy;

	@Column(name = "voided_at")
	private Instant voidedAt;

	@Column(name = "voided_by")
	private Long voidedBy;

	protected ReservationPriceLine() {
	}

	private ReservationPriceLine(Long reservationId, int lineNo, PriceBreakdown.Kind kind, String label) {
		this.reservationId = reservationId;
		this.lineNo = lineNo;
		this.kind = kind;
		this.label = label;
		this.amount = BigDecimal.ZERO;
	}

	/** Saha ücreti kalemi (fiyat hesaplayıcının çıktısından kopya). */
	public ReservationPriceLine(Long reservationId, int lineNo, PriceQuote.Line line) {
		this(reservationId, lineNo, PriceBreakdown.Kind.PITCH, line.label());
		this.startsAt = line.start();
		this.endsAt = line.end();
		this.minutes = line.minutes();
		this.hourlyRate = line.hourlyRate();
		this.amount = line.amount();
		this.priceRuleId = line.ruleId();
	}

	public static ReservationPriceLine extra(Long reservationId, int lineNo, Long extraServiceId, String name,
			int quantity, BigDecimal unitPrice, Long createdBy) {
		ReservationPriceLine l = new ReservationPriceLine(reservationId, lineNo, PriceBreakdown.Kind.EXTRA, name);
		l.extraServiceId = extraServiceId;
		l.quantity = quantity;
		l.unitAmount = unitPrice;
		l.createdBy = createdBy;
		return l;
	}

	public static ReservationPriceLine coupon(Long reservationId, int lineNo, Long couponId, String code,
			BigDecimal percent, BigDecimal fixed, Long createdBy) {
		ReservationPriceLine l = new ReservationPriceLine(reservationId, lineNo, PriceBreakdown.Kind.COUPON,
				"Kupon " + code);
		l.couponId = couponId;
		l.percent = percent;
		l.unitAmount = fixed;
		l.createdBy = createdBy;
		return l;
	}

	public static ReservationPriceLine staffDiscount(Long reservationId, int lineNo, BigDecimal percent,
			BigDecimal fixed, String reason, Long createdBy) {
		ReservationPriceLine l = new ReservationPriceLine(reservationId, lineNo, PriceBreakdown.Kind.STAFF_DISCOUNT,
				"Personel indirimi");
		l.percent = percent;
		l.unitAmount = fixed;
		l.reason = reason;
		l.createdBy = createdBy;
		return l;
	}

	/** PriceBreakdown hesabına girecek hâli. */
	public PriceBreakdown.Input toInput() {
		return switch (kind) {
			case PITCH -> PriceBreakdown.Input.pitch(amount);
			case EXTRA -> PriceBreakdown.Input.extra(quantity, unitAmount);
			case COUPON -> PriceBreakdown.Input.coupon(percent, unitAmount);
			case STAFF_DISCOUNT -> PriceBreakdown.Input.staffDiscount(percent, unitAmount);
		};
	}

	/** Yalnızca PriceBreakdown sonucunu yazmak için. Saha ücreti kalemleri hiçbir zaman değişmez. */
	void recalculatedAmount(BigDecimal newAmount) {
		if (kind == PriceBreakdown.Kind.PITCH) {
			return;
		}
		this.amount = newAmount;
	}

	public void voidLine(Long byUserId, Instant now) {
		if (kind == PriceBreakdown.Kind.PITCH) {
			throw new IllegalStateException("Saha ücreti kalemi kaldırılamaz");
		}
		this.voidedAt = now;
		this.voidedBy = byUserId;
	}

	public boolean isVoided() {
		return voidedAt != null;
	}

	public Long getId() {
		return id;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public int getLineNo() {
		return lineNo;
	}

	public PriceBreakdown.Kind getKind() {
		return kind;
	}

	public String getLabel() {
		return label;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public Integer getMinutes() {
		return minutes;
	}

	public BigDecimal getHourlyRate() {
		return hourlyRate;
	}

	public Integer getQuantity() {
		return quantity;
	}

	public BigDecimal getUnitAmount() {
		return unitAmount;
	}

	public BigDecimal getPercent() {
		return percent;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getReason() {
		return reason;
	}

	public Long getCouponId() {
		return couponId;
	}

}
