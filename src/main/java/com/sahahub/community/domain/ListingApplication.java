package com.sahahub.community.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** İlana başvuru. Rakip ilanında başvuran, kaptanı olduğu takımla başvurur (applicantTeamId). */
@Entity
@Table(name = "listing_application")
public class ListingApplication {

	public enum Status {

		PENDING("Yanıt bekliyor"), ACCEPTED("Kabul edildi"), REJECTED("Kabul edilmedi"), WITHDRAWN("Geri çekildi");

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

	@Column(name = "listing_id", nullable = false, updatable = false)
	private Long listingId;

	@Column(name = "applicant_id", nullable = false, updatable = false)
	private Long applicantId;

	@Column(name = "applicant_team_id", updatable = false)
	private Long applicantTeamId;

	@Column(updatable = false)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	protected ListingApplication() {
	}

	public ListingApplication(Long listingId, Long applicantId, Long applicantTeamId, String message, Instant now) {
		this.listingId = listingId;
		this.applicantId = applicantId;
		this.applicantTeamId = applicantTeamId;
		this.message = message;
		this.status = Status.PENDING;
		this.createdAt = now;
	}

	public void accept(Instant now) {
		decide(Status.ACCEPTED, now);
	}

	public void reject(Instant now) {
		decide(Status.REJECTED, now);
	}

	public void withdraw(Instant now) {
		decide(Status.WITHDRAWN, now);
	}

	private void decide(Status to, Instant now) {
		if (status != Status.PENDING) {
			throw new IllegalStateException("Başvuru " + id + " zaten yanıtlanmış: " + status);
		}
		this.status = to;
		this.decidedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getListingId() {
		return listingId;
	}

	public Long getApplicantId() {
		return applicantId;
	}

	public Long getApplicantTeamId() {
		return applicantTeamId;
	}

	public String getMessage() {
		return message;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

}
