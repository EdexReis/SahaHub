package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.identity.domain.StaffRole;

/**
 * Personel takviminin ekran modeli. Izgara 15 dakikalık satırlardan oluşur;
 * her öğe başlangıç satırı ve kaç satır kapladığı bilgisiyle gelir. Şablon yalnızca
 * bu sayıları CSS grid'e yerleştirir, hesap yapmaz.
 */
public record CalendarView(Long branchId, String branchName, String businessName, StaffRole role, Mode mode,
		LocalDate day, LocalDate weekStart, Long selectedPitchId, List<PitchOption> pitches, boolean open,
		int rowCount, List<TimeLabel> timeLabels, List<Column> columns, Summary summary, List<Upcoming> upcoming,
		boolean canManageBlocks, Integer nowRow) {

	public static final int MINUTES_PER_ROW = 15;

	public enum Mode {
		DAY, WEEK
	}

	public enum Kind {
		RESERVATION, BLOCK, BUFFER, FREE
	}

	public record PitchOption(Long id, String name) {
	}

	public record TimeLabel(String text, int row) {
	}

	public record Column(Long pitchId, LocalDate day, String title, String subtitle, boolean closed, boolean today,
			List<Item> items) {
	}

	/**
	 * Takvim öğesi. FREE öğeler tıklanınca hızlı rezervasyon formunu açar; start, formun
	 * önceden dolacağı yerel başlangıç zamanıdır.
	 */
	public record Item(Kind kind, int rowStart, int rowSpan, String code, String title, String subtitle,
			ReservationStatus status, Channel channel, boolean checkedIn, Long pitchId, LocalDateTime start,
			Long blockId) {

		public boolean compact() {
			return rowSpan <= 3;
		}

	}

	/** Günün özet sayıları. bookedAmount = açık ve tamamlanmış rezervasyon bedellerinin toplamı (tahsilat değildir). */
	public record Summary(int reservations, int held, int checkedIn, int freeSlots, BigDecimal bookedAmount,
			String currency) {
	}

	public record Upcoming(String code, ZonedDateTime start, ZonedDateTime end, String pitchName, String customerName,
			ReservationStatus status, boolean checkedIn) {
	}

}
