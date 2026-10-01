package com.sahahub.booking.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.sahahub.shared.domain.BusinessRuleException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Düzenli rezervasyon tanımı: "ilk tarihten başlayarak her hafta aynı gün ve saatte, N kez".
 * Seri tek başına sahayı meşgul etmez; her maç ayrı bir {@link Reservation}'dır ve seriye bağlıdır.
 * Sınırsız tekrar yoktur; adet sınırı ayarlarla belirlenir.
 */
@Entity
@Table(name = "reservation_series")
public class ReservationSeries {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "business_id", nullable = false, updatable = false)
	private Long businessId;

	@Column(name = "branch_id", nullable = false, updatable = false)
	private Long branchId;

	@Column(name = "pitch_id", nullable = false, updatable = false)
	private Long pitchId;

	@Column(name = "customer_id", updatable = false)
	private Long customerId;

	@Column(name = "guest_name", updatable = false)
	private String guestName;

	@Column(name = "guest_phone", updatable = false)
	private String guestPhone;

	@Column(name = "first_date", nullable = false, updatable = false)
	private LocalDate firstDate;

	@Column(name = "start_time", nullable = false, updatable = false)
	private LocalTime startTime;

	@Column(name = "duration_minutes", nullable = false, updatable = false)
	private int durationMinutes;

	@Column(nullable = false, updatable = false)
	private int occurrences;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Channel channel;

	@Column(updatable = false)
	private String note;

	@Column(name = "created_by", nullable = false, updatable = false)
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ReservationSeries() {
	}

	public ReservationSeries(Long businessId, Long branchId, Long pitchId, Long customerId, String guestName,
			String guestPhone, LocalDate firstDate, LocalTime startTime, int durationMinutes, int occurrences,
			Channel channel, String note, Long createdBy, Instant now) {
		if (customerId == null && (guestName == null || guestName.isBlank())) {
			throw new BusinessRuleException("Müşteri adını yazın ya da kayıtlı e-postasını girin.");
		}
		this.businessId = businessId;
		this.branchId = branchId;
		this.pitchId = pitchId;
		this.customerId = customerId;
		this.guestName = guestName == null || guestName.isBlank() ? null : guestName.strip();
		this.guestPhone = guestPhone == null || guestPhone.isBlank() ? null : guestPhone.strip();
		this.firstDate = firstDate;
		this.startTime = startTime;
		this.durationMinutes = durationMinutes;
		this.occurrences = occurrences;
		this.channel = channel;
		this.note = note == null || note.isBlank() ? null : note.strip();
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	/** Serinin tüm tarihleri: ilk tarihten başlayarak her hafta aynı gün. */
	public static List<LocalDate> dates(LocalDate firstDate, int occurrences) {
		List<LocalDate> list = new ArrayList<>(occurrences);
		for (int i = 0; i < occurrences; i++) {
			list.add(firstDate.plusWeeks(i));
		}
		return list;
	}

	public List<LocalDate> dates() {
		return dates(firstDate, occurrences);
	}

	public DayOfWeek dayOfWeek() {
		return firstDate.getDayOfWeek();
	}

	public Long getId() {
		return id;
	}

	public Long getBusinessId() {
		return businessId;
	}

	public Long getBranchId() {
		return branchId;
	}

	public Long getPitchId() {
		return pitchId;
	}

	public Long getCustomerId() {
		return customerId;
	}

	public String getGuestName() {
		return guestName;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public LocalDate getFirstDate() {
		return firstDate;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public int getOccurrences() {
		return occurrences;
	}

	public Channel getChannel() {
		return channel;
	}

	public String getNote() {
		return note;
	}

}
