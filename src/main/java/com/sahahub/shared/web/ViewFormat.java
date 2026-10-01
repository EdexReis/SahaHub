package com.sahahub.shared.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.sahahub.shared.domain.Money;

/**
 * Şablonlarda kullanılan Türkçe biçimlendirme yardımcıları: ${@fmt.money(...)} gibi.
 * Biçimlendirme servis katmanında değil burada yapılır; servisler ham değer döner.
 */
@Component("fmt")
public class ViewFormat {

	private static final Locale TR = Locale.forLanguageTag("tr-TR");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", TR);
	private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM EEEE", TR);
	private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE", TR);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", TR);

	private final Clock clock;

	public ViewFormat(Clock clock) {
		this.clock = clock;
	}

	public String money(BigDecimal amount, String currency) {
		return amount == null ? "" : Money.format(amount, currency);
	}

	public String moneyShort(BigDecimal amount, String currency) {
		return amount == null ? "" : Money.formatShort(amount, currency);
	}

	public String time(ZonedDateTime t) {
		return t == null ? "" : t.format(TIME);
	}

	public String timeRange(ZonedDateTime start, ZonedDateTime end) {
		return time(start) + "–" + time(end);
	}

	public String longDate(LocalDate d) {
		return d == null ? "" : d.format(LONG_DATE);
	}

	public String dayMonth(LocalDate d) {
		return d == null ? "" : d.format(DAY_MONTH);
	}

	/** Belirli bir şubeye bağlı olmayan anlar (bildirim zamanı gibi) Türkiye saatiyle gösterilir. */
	public String at(java.time.Instant t) {
		return t == null ? "" : t.atZone(java.time.ZoneId.of("Europe/Istanbul")).format(DATE_TIME);
	}

	public String dateTime(ZonedDateTime t) {
		return t == null ? "" : t.format(DATE_TIME);
	}

	/** "Bugün", "Yarın" ya da kısa gün adı ("Cum"). */
	public String dayLabel(LocalDate d, String zoneId) {
		LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of(zoneId)));
		if (d.equals(today)) {
			return "Bugün";
		}
		if (d.equals(today.plusDays(1))) {
			return "Yarın";
		}
		return d.format(SHORT_DAY);
	}

	public String shortDay(LocalDate d) {
		return d.format(SHORT_DAY);
	}

	public String duration(int minutes) {
		int h = minutes / 60;
		int m = minutes % 60;
		if (h == 0) {
			return m + " dk";
		}
		return m == 0 ? h + " saat" : h + " sa " + m + " dk";
	}

	/**
	 * Formlara gizli alan olarak konan tekil istek anahtarı (idempotency key). Form iki kez gönderilse
	 * (çift tıklama, geri tuşu) aynı anahtar gelir ve sunucu ikinci bir ödeme hareketi oluşturmaz.
	 */
	public String newKey() {
		return java.util.UUID.randomUUID().toString();
	}

	/** Haftanın günü kısa adı: "Pzt". */
	public String dayShort(java.time.DayOfWeek d) {
		return d.getDisplayName(java.time.format.TextStyle.SHORT, TR);
	}

	/** Takvimdeki dar kutular için kısa ödeme etiketi. */
	public String payShort(String state) {
		return switch (state) {
			case "DEPOSIT_DUE" -> "Kapora";
			case "PARTIAL" -> "Kısmi";
			case "PAID" -> "Ödendi";
			case "REFUND_DUE" -> "İade";
			default -> "";
		};
	}

	/** "%10" biçiminde yüzde. */
	public String percent(BigDecimal p) {
		return p == null ? "" : "%" + p.stripTrailingZeros().toPlainString().replace('.', ',');
	}

	/** Takvim gün başlığı ve tarih seçici için ISO tarih: 2026-10-01 */
	public String iso(LocalDate d) {
		return d.toString();
	}

}
