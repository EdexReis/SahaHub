package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.sahahub.shared.domain.Money;

/**
 * Kapora kuralı. Kapora, indirimler uygulandıktan sonraki SON toplam üzerinden hesaplanır.
 * <ul>
 * <li>NONE: kapora yok; çevrim içi rezervasyon ödemesiz onaylanır.</li>
 * <li>FIXED: sabit tutar; toplamdan büyükse toplam kadar.</li>
 * <li>PERCENT: toplamın yüzdesi, kuruşa HALF_UP yuvarlanır.</li>
 * </ul>
 */
public record DepositPolicy(Type type, BigDecimal value) {

	public enum Type {

		NONE("Kapora yok"), FIXED("Sabit tutar"), PERCENT("Yüzde");

		private final String label;

		Type(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public static final DepositPolicy NONE = new DepositPolicy(Type.NONE, BigDecimal.ZERO);

	public DepositPolicy {
		Objects.requireNonNull(type);
		value = value == null ? BigDecimal.ZERO : value;
		if (value.signum() < 0) {
			throw new IllegalArgumentException("Kapora negatif olamaz");
		}
		if (type == Type.PERCENT && value.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw new IllegalArgumentException("Kapora yüzdesi 100'ü aşamaz");
		}
	}

	public boolean required() {
		return type != Type.NONE && value.signum() > 0;
	}

	public BigDecimal depositFor(BigDecimal total) {
		if (!required() || total.signum() <= 0) {
			return Money.round(BigDecimal.ZERO);
		}
		BigDecimal deposit = switch (type) {
			case FIXED -> value.min(total);
			case PERCENT -> total.multiply(value).divide(BigDecimal.valueOf(100), Money.SCALE, Money.ROUNDING);
			case NONE -> BigDecimal.ZERO;
		};
		return Money.round(deposit);
	}

}
