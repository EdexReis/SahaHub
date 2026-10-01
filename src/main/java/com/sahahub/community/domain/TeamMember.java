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

/** Takım üyeliği. Ayrılan üyenin satırı silinmez (left_at); yeniden katılırsa yeni satır açılır. */
@Entity
@Table(name = "team_member")
public class TeamMember {

	public enum Role {

		CAPTAIN("Kaptan"), MEMBER("Oyuncu");

		private final String label;

		Role(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false, updatable = false)
	private Long teamId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;

	@Column(name = "left_at")
	private Instant leftAt;

	protected TeamMember() {
	}

	public TeamMember(Long teamId, Long userId, Role role, Instant now) {
		this.teamId = teamId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = now;
	}

	public void leave(Instant now) {
		if (leftAt != null) {
			throw new IllegalStateException("Üyelik zaten sona ermiş: " + id);
		}
		this.leftAt = now;
	}

	public void promoteToCaptain() {
		this.role = Role.CAPTAIN;
	}

	public void demoteToMember() {
		this.role = Role.MEMBER;
	}

	public boolean isCaptain() {
		return role == Role.CAPTAIN;
	}

	public Long getId() {
		return id;
	}

	public Long getTeamId() {
		return teamId;
	}

	public Long getUserId() {
		return userId;
	}

	public Role getRole() {
		return role;
	}

	public Instant getJoinedAt() {
		return joinedAt;
	}

	public Instant getLeftAt() {
		return leftAt;
	}

}
