package com.sahahub.booking.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.sahahub.booking.app.SeriesService;

import jakarta.validation.constraints.NotNull;

/** Düzenli (haftalık) rezervasyon formu: hızlı rezervasyon alanları + tekrar sayısı + seçilen tarihler. */
public class SeriesForm extends QuickBookingForm {

	@NotNull(message = "Kaç hafta tekrar edeceğini seçin.")
	private Integer occurrences = 8;

	/** "Seçili tarihleri oluştur" modunda işaretlenen tarihler. */
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
	private List<LocalDate> chosen = new ArrayList<>();

	public SeriesService.SeriesCommand toSeriesCommand() {
		return new SeriesService.SeriesCommand(getPitchId(), getDate(), getTime(), getDurationMinutes(), occurrences,
				getChannel(), getCustomerEmail(), getGuestName(), getGuestPhone(), getNote());
	}

	public Integer getOccurrences() {
		return occurrences;
	}

	public void setOccurrences(Integer occurrences) {
		this.occurrences = occurrences;
	}

	public List<LocalDate> getChosen() {
		return chosen;
	}

	public void setChosen(List<LocalDate> chosen) {
		this.chosen = chosen;
	}

}
