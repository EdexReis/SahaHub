package com.sahahub.booking.domain;

import java.time.Instant;

import com.sahahub.shared.domain.TimeRange;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Dolu bir saat için sıraya giren müşteri.
 *
 * <pre>
 * WAITING ──teklif──► OFFERED ──onay──► ACCEPTED
 *    │                   └──süre doldu / bıraktı──► EXPIRED (sıradakine geçilir)
 *    └──sıradan çıktı──► LEFT
 * </pre>
 *
 * Teklif, müşteri adına açılmış bir geçici tutmadır (offerReservationId). Saat o tutmayla
 * sahada meşgul olduğu için başka biri alamaz; tutma onaylanırsa kayıt ACCEPTED olur.
 */
@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {

	public enum Status {

		WAITING("Sırada"), OFFERED("Teklif edildi"), ACCEPTED("Rezervasyona dönüştü"), EXPIRED("Teklif süresi doldu"),
		LEFT("Sıradan çıkıldı");

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

	@Column(name = "pitch_id", nullable = false, updatable = false)
	private Long pitchId;

	@Column(name = "customer_id", nullable = false, updatable = false)
	private Long customerId;

	@Column(name = "starts_at", nullable = false, updatable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false, updatable = false)
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "offer_reservation_id")
	private Long offerReservationId;

	@Column(name = "offered_at")
	private Instant offeredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	protected WaitlistEntry() {
	}

	public WaitlistEntry(Long businessId, Long branchId, Long pitchId, Long customerId, TimeRange play, Instant now) {
		this.businessId = businessId;
		this.branchId = branchId;
		this.pitchId = pitchId;
		this.customerId = customerId;
		this.startsAt = play.start();
		this.endsAt = play.end();
		this.status = Status.WAITING;
		this.createdAt = now;
	}

	public void offer(Long reservationId, Instant now) {
		require(Status.WAITING);
		this.status = Status.OFFERED;
		this.offerReservationId = reservationId;
		this.offeredAt = now;
	}

	public void accept(Instant now) {
		require(Status.OFFERED);
		this.status = Status.ACCEPTED;
		this.closedAt = now;
	}

	/** Teklif kabul edilmedi (süre doldu veya müşteri bıraktı). */
	public void expireOffer(Instant now) {
		require(Status.OFFERED);
		this.status = Status.EXPIRED;
		this.closedAt = now;
	}

	/** Saat geçti ve teklif yapılamadı. */
	public void expireUnserved(Instant now) {
		require(Status.WAITING);
		this.status = Status.EXPIRED;
		this.closedAt = now;
	}

	public void leave(Instant now) {
		require(Status.WAITING);
		this.status = Status.LEFT;
		this.closedAt = now;
	}

	private void require(Status expected) {
		if (status != expected) {
			throw new IllegalStateException("Bekleme kaydı " + id + " durumu " + status + ", beklenen " + expected);
		}
	}

	public TimeRange play() {
		return new TimeRange(startsAt, endsAt);
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

	public Long getPitchId() {
		return pitchId;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public Status getStatus() {
		return status;
	}

	public Long getOfferReservationId() {
		return offerReservationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
