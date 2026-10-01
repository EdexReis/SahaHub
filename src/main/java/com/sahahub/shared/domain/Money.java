package com.sahahub.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Para hesaplarında ortak kurallar.
 * <ul>
 * <li>Tüm tutarlar BigDecimal; double/float kullanılmaz (0.1 + 0.2 sorunu).</li>
 * <li>Saklanan ölçek 2 basamaktır (kuruş).</li>
 * <li>Yuvarlama HALF_UP: 0,005 ve üstü yukarı yuvarlanır.</li>
 * <li>İlk sürümde tek para birimi TRY'dir.</li>
 * </ul>
 */
public final class Money {

	public static final int SCALE = 2;
	public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
	public static final String DEFAULT_CURRENCY = "TRY";

	private Money() {
	}

	public static BigDecimal round(BigDecimal value) {
		return value.setScale(SCALE, ROUNDING);
	}

	/** Kullanıcıya gösterim: "1.250,00 ₺" */
	public static String format(BigDecimal amount, String currency) {
		NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR"));
		nf.setMinimumFractionDigits(2);
		nf.setMaximumFractionDigits(2);
		String symbol = "TRY".equals(currency) ? "₺" : currency;
		return nf.format(amount) + " " + symbol;
	}

	/** Kuruşsuz kısa gösterim (saat kartları için): "1.250 ₺" */
	public static String formatShort(BigDecimal amount, String currency) {
		if (amount.stripTrailingZeros().scale() > 0) {
			return format(amount, currency);
		}
		NumberFormat nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag("tr-TR"));
		String symbol = "TRY".equals(currency) ? "₺" : currency;
		return nf.format(amount) + " " + symbol;
	}

}
