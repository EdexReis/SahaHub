package com.sahahub.shared.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Kritik değişikliklerin kaydı. Uygulama bu tabloda güncelleme/silme yapmaz. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "actor_id")
	private Long actorId;

	@Column(name = "business_id")
	private Long businessId;

	@Column(nullable = false)
	private String action;

	@Column(name = "entity_type", nullable = false)
	private String entityType;

	@Column(name = "entity_id")
	private Long entityId;

	private String details;

	@Column(name = "request_id")
	private String requestId;

	protected AuditEvent() {
	}

	AuditEvent(Instant occurredAt, Long actorId, Long businessId, String action, String entityType, Long entityId,
			String details, String requestId) {
		this.occurredAt = occurredAt;
		this.actorId = actorId;
		this.businessId = businessId;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.details = details;
		this.requestId = requestId;
	}

	public Long getId() {
		return id;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Long getActorId() {
		return actorId;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public String getAction() {
		return action;
	}

	public String getEntityType() {
		return entityType;
	}

	public Long getEntityId() {
		return entityId;
	}

	public String getDetails() {
		return details;
	}

}
