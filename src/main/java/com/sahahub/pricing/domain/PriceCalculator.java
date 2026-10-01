package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sahahub.shared.domain.Money;
import com.sahahub.shared.domain.TimeRange;

/**
 * Rezervasyon ücretini hesaplar. Saf bir sınıftır: veritabanına erişmez.
 *
 * <h2>Kurallar</h2>
 * <ol>
 * <li>Süre dakika dakika ele alınır; her dakikaya, şubenin yerel saatinde o dakikanın
 * BAŞLANGICINA uyan kural uygulanır. Böylece 17:30-18:30 maçında 18:00'de başlayan
 * akşam tarifesi, maçın son 30 dakikasına uygulanır. Gece yarısını aşan maçlarda
 * 00:00'dan sonraki dakikalar ertesi günün kurallarına göre fiyatlanır.</li>
 * <li>Bir dakikaya birden fazla kural uyuyorsa en yüksek priority kazanır; eşitlikte
 * id'si büyük (daha yeni) kural kazanır. Hiçbiri uymuyorsa sahanın standart ücreti.</li>
 * <li>Aynı ücretin uygulandığı ardışık dakikalar tek kalemde toplanır.</li>
 * <li>Kalem tutarı = saatlik ücret × dakika / 60, kuruşa HALF_UP yuvarlanır.
 * Toplam = kalemlerin toplamı (toplam ayrıca yuvarlanmaz).</li>
 * <li>Hazırlık süresi (buffer) ücretlendirilmez.</li>
 * </ol>
 * Kapora, kupon, indirim ve ek hizmetler sonraki aşamada bu toplamın üzerine eklenecek.
 */
public final class PriceCalculator {

	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
	private static final String STANDARD_LABEL = "Standart ücret";

	private static final Comparator<PriceRule> PRECEDENCE = Comparator.comparingInt(PriceRule::getPriority)
		.thenComparing(PriceRule::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

	private PriceCalculator() {
	}

	public static PriceQuote quote(TimeRange range, ZoneId zone, BigDecimal baseHourlyPrice, String currency,
			List<PriceRule> rules) {
		List<PriceQuote.Line> lines = new ArrayList<>();
		Instant segmentStart = range.start();
		PriceRule segmentRule = winningRule(rules, LocalDateTime.ofInstant(segmentStart, zone));

		Instant cursor = segmentStart.plusSeconds(60);
		while (cursor.isBefore(range.end())) {
			PriceRule rule = winningRule(rules, LocalDateTime.ofInstant(cursor, zone));
			// Aynı liste elemanı döndüğü için referans karşılaştırması yeterli (null = standart ücret)
			if (rule != segmentRule) {
				lines.add(line(segmentStart, cursor, segmentRule, baseHourlyPrice));
				segmentStart = cursor;
				segmentRule = rule;
			}
			cursor = cursor.plusSeconds(60);
		}
		lines.add(line(segmentStart, range.end(), segmentRule, baseHourlyPrice));

		BigDecimal total = lines.stream().map(PriceQuote.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new PriceQuote(lines, Money.round(total), currency);
	}

	private static PriceRule winningRule(List<PriceRule> rules, LocalDateTime minute) {
		return rules.stream().filter(r -> r.appliesAt(minute)).max(PRECEDENCE).orElse(null);
	}

	private static PriceQuote.Line line(Instant start, Instant end, PriceRule rule, BigDecimal base) {
		int minutes = (int) new TimeRange(start, end).minutes();
		BigDecimal rate = rule == null ? base : rule.getHourlyPrice();
		BigDecimal amount = rate.multiply(BigDecimal.valueOf(minutes)).divide(SIXTY, Money.SCALE, Money.ROUNDING);
		return new PriceQuote.Line(rule == null ? STANDARD_LABEL : rule.getName(), start, end, minutes,
				Money.round(rate), amount, rule == null ? null : rule.getId());
	}

}
