package com.sahahub.business.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Platformdaki bir işletme (kiracı). Tüm şube, saha ve rezervasyon kayıtları buna bağlıdır. */
@Entity
@Table(name = "business")
public class Business {

	public enum Status {
		ACTIVE, SUSPENDED, ARCHIVED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private String description;

	private String phone;

	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Business() {
	}

	public Business(String name, String description, String phone, String email, Instant createdAt) {
		this.name = name;
		this.description = description;
		this.phone = phone;
		this.email = email;
		this.createdAt = createdAt;
	}

	public void suspend() {
		this.status = Status.SUSPENDED;
	}

	public void activate() {
		this.status = Status.ACTIVE;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public Long getId() {
		return id;
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

	public String getEmail() {
		return email;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
