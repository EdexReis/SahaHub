package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.ReservationStatus;

/**
 * Rezervasyonun ekranda gösterilen hâli. Entity yerine bu kayıt (record) şablonlara verilir:
 * şablon yalnızca göstermesi gerekeni görür, tembel yükleme (lazy loading) sürprizi olmaz.
 * Tüm zamanlar şubenin zaman dilimindedir. businessDay: maçın ait olduğu iş günü — gece 00:30'daki
 * maç önceki günün takviminde görünür, bu yüzden takvime dönüşte bu tarih kullanılır.
 */
public record ReservationView(String code, ReservationStatus status, Channel channel, Long pitchId,
		String pitchName, Long branchId, String branchName, String businessName, String address,
		ZonedDateTime start, ZonedDateTime end, int durationMinutes, int bufferMinutes, BigDecimal total,
		String currency, List<Line> lines, ZonedDateTime holdExpiresAt, boolean customerCanCancel,
		ZonedDateTime customerCancelDeadline, String customerName, String contactPhone, String note,
		ZonedDateTime checkedInAt, String cancelReason, ZonedDateTime createdAt, LocalDate businessDay,
		Actions actions, ZonedDateTime checkInOpensAt) {

	/** Personelin şu an yapabileceği işlemler (sunucu yine de her komutta yeniden doğrular). */
	public record Actions(boolean checkIn, boolean complete, boolean noShow, boolean move, boolean cancel) {

		public boolean anyStatusChange() {
			return checkIn || complete || noShow;
		}

	}

	/** Gece yarısından sonra başlayan ve önceki iş gününe ait maç mı? (ör. Cuma gecesi 00:30) */
	public boolean overnight() {
		return !start.toLocalDate().equals(businessDay);
	}

	public record Line(String label, ZonedDateTime start, ZonedDateTime end, int minutes, BigDecimal hourlyRate,
			BigDecimal amount) {
	}

	public boolean held() {
		return status == ReservationStatus.HELD;
	}

	public boolean open() {
		return status.isOpen();
	}

}
