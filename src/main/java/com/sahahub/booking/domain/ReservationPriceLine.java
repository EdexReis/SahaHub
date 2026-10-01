package com.sahahub.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.sahahub.pricing.domain.PriceQuote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Rezervasyon anındaki fiyat kaleminin değişmez kopyası. Tarife sonradan değişse de
 * bu satırlar ve rezervasyon toplamı değişmez.
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

	@Column(nullable = false, updatable = false)
	private String label;

	@Column(name = "starts_at", nullable = false, updatable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false, updatable = false)
	private Instant endsAt;

	@Column(nullable = false, updatable = false)
	private int minutes;

	@Column(name = "hourly_rate", nullable = false, updatable = false)
	private BigDecimal hourlyRate;

	@Column(nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(name = "price_rule_id", updatable = false)
	private Long priceRuleId;

	protected ReservationPriceLine() {
	}

	public ReservationPriceLine(Long reservationId, int lineNo, PriceQuote.Line line) {
		this.reservationId = reservationId;
		this.lineNo = lineNo;
		this.label = line.label();
		this.startsAt = line.start();
		this.endsAt = line.end();
		this.minutes = line.minutes();
		this.hourlyRate = line.hourlyRate();
		this.amount = line.amount();
		this.priceRuleId = line.ruleId();
	}

	public int getLineNo() {
		return lineNo;
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

	public int getMinutes() {
		return minutes;
	}

	public BigDecimal getHourlyRate() {
		return hourlyRate;
	}

	public BigDecimal getAmount() {
		return amount;
	}

}
