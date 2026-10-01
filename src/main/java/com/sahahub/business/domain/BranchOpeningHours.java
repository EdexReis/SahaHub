package com.sahahub.business.domain;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Haftalık çalışma saati satırı (şube + haftanın günü). */
@Entity
@Table(name = "branch_opening_hours")
@IdClass(BranchOpeningHours.Key.class)
public class BranchOpeningHours {

	public static class Key implements Serializable {

		private Long branchId;
		private short dayOfWeek;

		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && Objects.equals(branchId, k.branchId) && dayOfWeek == k.dayOfWeek;
		}

		@Override
		public int hashCode() {
			return Objects.hash(branchId, dayOfWeek);
		}

	}

	@Id
	@Column(name = "branch_id")
	private Long branchId;

	/** ISO: 1 = Pazartesi ... 7 = Pazar */
	@Id
	@Column(name = "day_of_week")
	private short dayOfWeek;

	@Column(nullable = false)
	private boolean closed;

	@Column(name = "open_time")
	private LocalTime openTime;

	@Column(name = "close_time")
	private LocalTime closeTime;

	protected BranchOpeningHours() {
	}

	public BranchOpeningHours(Long branchId, DayOfWeek day, DayHours hours) {
		this.branchId = branchId;
		this.dayOfWeek = (short) day.getValue();
		this.closed = hours.closed();
		this.openTime = hours.open();
		this.closeTime = hours.close();
	}

	public DayOfWeek day() {
		return DayOfWeek.of(dayOfWeek);
	}

	public DayHours hours() {
		return closed ? DayHours.CLOSED : DayHours.open(openTime, closeTime);
	}

	public Long getBranchId() {
		return branchId;
	}

}
