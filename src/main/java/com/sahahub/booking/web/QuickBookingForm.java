package com.sahahub.booking.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Personelin hızlı rezervasyon formu. */
public class QuickBookingForm {

	@NotNull(message = "Saha seçin.")
	private Long pitchId;

	@NotNull(message = "Tarih seçin.")
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private LocalDate date;

	@NotNull(message = "Saat seçin.")
	@DateTimeFormat(pattern = "HH:mm")
	private LocalTime time;

	@NotNull(message = "Süre seçin.")
	private Integer durationMinutes = 60;

	@NotNull
	private Channel channel = Channel.PHONE;

	@Email(message = "Geçerli bir e-posta yazın.")
	@Size(max = 254)
	private String customerEmail;

	@Size(max = 120, message = "En fazla 120 karakter.")
	private String guestName;

	@Pattern(regexp = "^$|^[0-9 +()-]{10,20}$", message = "Telefonu 0532 123 45 67 biçiminde yazın.")
	private String guestPhone;

	@Size(max = 500, message = "Not en fazla 500 karakter.")
	private String note;

	public StaffReservationService.CreateCommand toCommand() {
		return new StaffReservationService.CreateCommand(pitchId, LocalDateTime.of(date, time), durationMinutes,
				channel, customerEmail, guestName, guestPhone, note);
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

	public Integer getDurationMinutes() {
		return durationMinutes;
	}

	public void setDurationMinutes(Integer durationMinutes) {
		this.durationMinutes = durationMinutes;
	}

	public Channel getChannel() {
		return channel;
	}

	public void setChannel(Channel channel) {
		this.channel = channel;
	}

	public String getCustomerEmail() {
		return customerEmail;
	}

	public void setCustomerEmail(String customerEmail) {
		this.customerEmail = customerEmail;
	}

	public String getGuestName() {
		return guestName;
	}

	public void setGuestName(String guestName) {
		this.guestName = guestName;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public void setGuestPhone(String guestPhone) {
		this.guestPhone = guestPhone;
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

}
