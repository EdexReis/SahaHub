package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Hesaplanan fiyat: kalemler ve toplam. Rezervasyona anlık görüntü olarak kopyalanır. */
public record PriceQuote(List<Line> lines, BigDecimal total, String currency) {

	/**
	 * Bir fiyat kalemi: aynı saatlik ücretin uygulandığı kesintisiz süre.
	 * ruleId boşsa sahanın standart ücreti uygulanmıştır.
	 */
	public record Line(String label, Instant start, Instant end, int minutes, BigDecimal hourlyRate,
			BigDecimal amount, Long ruleId) {
	}

	public PriceQuote {
		lines = List.copyOf(lines);
	}

}
