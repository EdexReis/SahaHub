package com.sahahub.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Bir kullanıcının bir işletmedeki personel görevi.
 * OWNER için branchId boştur (tüm şubeler); diğer roller tek bir şubeye bağlıdır.
 */
@Entity
@Table(name = "staff_membership")
public class StaffMembership {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "business_id", nullable = false)
	private Long businessId;

	@Column(name = "branch_id")
	private Long branchId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private StaffRole role;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected StaffMembership() {
	}

	private StaffMembership(Long userId, Long businessId, Long branchId, StaffRole role, Instant createdAt) {
		this.userId = userId;
		this.businessId = businessId;
		this.branchId = branchId;
		this.role = role;
		this.createdAt = createdAt;
	}

	public static StaffMembership owner(Long userId, Long businessId, Instant now) {
		return new StaffMembership(userId, businessId, null, StaffRole.OWNER, now);
	}

	public static StaffMembership forBranch(Long userId, Long businessId, Long branchId, StaffRole role, Instant now) {
		if (role == StaffRole.OWNER) {
			throw new IllegalArgumentException("OWNER bir şubeye değil işletmeye atanır");
		}
		return new StaffMembership(userId, businessId, branchId, role, now);
	}

	/** Bu görev verilen şubeyi kapsıyor mu? */
	public boolean covers(Long branchBusinessId, Long branchId) {
		if (!active || !businessId.equals(branchBusinessId)) {
			return false;
		}
		return role == StaffRole.OWNER || this.branchId.equals(branchId);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public StaffRole getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

}
