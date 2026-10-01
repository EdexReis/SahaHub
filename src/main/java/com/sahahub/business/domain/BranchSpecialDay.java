package com.sahahub.business.domain;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Tatil günü (kapalı) veya o güne özel çalışma saati. Haftalık planın önüne geçer. */
@Entity
@Table(name = "branch_special_day")
public class BranchSpecialDay {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "branch_id", nullable = false)
	private Long branchId;

	@Column(nullable = false)
	private LocalDate day;

	@Column(nullable = false)
	private boolean closed;

	@Column(name = "open_time")
	private LocalTime openTime;

	@Column(name = "close_time")
	private LocalTime closeTime;

	private String note;

	protected BranchSpecialDay() {
	}

	public BranchSpecialDay(Long branchId, LocalDate day, DayHours hours, String note) {
		this.branchId = branchId;
		this.day = day;
		this.closed = hours.closed();
		this.openTime = hours.open();
		this.closeTime = hours.close();
		this.note = note;
	}

	public LocalDate getDay() {
		return day;
	}

	public String getNote() {
		return note;
	}

	public DayHours hours() {
		return closed ? DayHours.CLOSED : DayHours.open(openTime, closeTime);
	}

}
