package com.sahahub.booking.web;

import java.time.LocalDate;
import java.time.LocalTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.sahahub.booking.app.ReservationView;

/** Rezervasyonu taşıma formu; varsayılan değerler mevcut saha ve saattir. */
public class MoveForm {

	private Long pitchId;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate date;

	@DateTimeFormat(pattern = "HH:mm")
	private LocalTime time;

	public static MoveForm from(ReservationView r) {
		MoveForm f = new MoveForm();
		f.pitchId = r.pitchId();
		f.date = r.start().toLocalDate();
		f.time = r.start().toLocalTime();
		return f;
	}

	public Long getPitchId() {
		return pitchId;
	}

	public void setPitchId(Long pitchId) {
		this.pitchId = pitchId;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public LocalTime getTime() {
		return time;
	}

	public void setTime(LocalTime time) {
		this.time = time;
	}

}
