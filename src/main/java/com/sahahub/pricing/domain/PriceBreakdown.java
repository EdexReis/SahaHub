package com.sahahub.pricing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.sahahub.shared.domain.Money;

/**
 * Rezervasyon kalemlerinden son tutarı hesaplar. Saf bir sınıftır.
 *
 * <h2>Sıra (değiştirilemez)</h2>
 * <ol>
 * <li><b>Saha ücreti</b> (PITCH): rezervasyon anında kopyalanmış, değişmez tutarlar.</li>
 * <li><b>Ek hizmetler</b> (EXTRA): adet × birim fiyat.</li>
 * <li>Ara toplam = 1 + 2.</li>
 * <li><b>Kupon</b> (en fazla bir): ara toplamın yüzdesi veya sabit tutar; ara toplamı aşamaz.</li>
 * <li><b>Personel indirimi</b>: kupondan SONRA kalan tutarın yüzdesi veya sabit tutar; kalanı aşamaz.</li>
 * <li>Toplam = ara toplam − kupon − personel indirimi (asla negatif değil).</li>
 * </ol>
 * Yüzde indirimler kuruşa HALF_UP yuvarlanır. İndirim kalemleri negatif tutarla saklanır.
 * Kalemlerin giriş sırası sonucu değiştirmez: hesap her seferinde bu sırayla yeniden yapılır.
 */
public final class PriceBreakdown {

	public enum Kind {
		PITCH, EXTRA, COUPON, STAFF_DISCOUNT
	}

	/**
	 * Hesaba giren kalem. PITCH için fixedAmount, EXTRA için quantity × unitAmount,
	 * indirimler için percent VEYA unitAmount (sabit indirim tutarı, pozitif) kullanılır.
	 */
	public record Input(Kind kind, BigDecimal fixedAmount, Integer quantity, BigDecimal unitAmount,
			BigDecimal percent) {

		public static Input pitch(BigDecimal amount) {
			return new Input(Kind.PITCH, amount, null, null, null);
		}

		public static Input extra(int quantity, BigDecimal unitPrice) {
			return new Input(Kind.EXTRA, null, quantity, unitPrice, null);
		}

		public static Input coupon(BigDecimal percent, BigDecimal fixed) {
			return new Input(Kind.COUPON, null, null, fixed, percent);
		}

		public static Input staffDiscount(BigDecimal percent, BigDecimal fixed) {
			return new Input(Kind.STAFF_DISCOUNT, null, null, fixed, percent);
		}

	}

	/** amounts: girdilerle aynı sırada, her kalemin hesaplanan tutarı (indirimler negatif). */
	public record Result(List<BigDecimal> amounts, BigDecimal subtotal, BigDecimal discounts, BigDecimal total) {
	}

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private PriceBreakdown() {
	}

	public static Result compute(List<Input> inputs) {
		BigDecimal[] amounts = new BigDecimal[inputs.size()];
		BigDecimal subtotal = BigDecimal.ZERO;
		for (int i = 0; i < inputs.size(); i++) {
			Input in = inputs.get(i);
			if (in.kind() == Kind.PITCH) {
				amounts[i] = Money.round(in.fixedAmount());
			}
			else if (in.kind() == Kind.EXTRA) {
				amounts[i] = Money.round(in.unitAmount().multiply(BigDecimal.valueOf(in.quantity())));
			}
			if (amounts[i] != null) {
				subtotal = subtotal.add(amounts[i]);
			}
		}
		BigDecimal remaining = subtotal;
		for (Kind discountKind : new Kind[] { Kind.COUPON, Kind.STAFF_DISCOUNT }) {
			for (int i = 0; i < inputs.size(); i++) {
				Input in = inputs.get(i);
				if (in.kind() != discountKind) {
					continue;
				}
				BigDecimal discount = in.percent() != null
						? remaining.multiply(in.percent()).divide(HUNDRED, Money.SCALE, Money.ROUNDING)
						: in.unitAmount();
				discount = Money.round(discount.min(remaining));
				amounts[i] = discount.negate();
				remaining = remaining.subtract(discount);
			}
		}
		List<BigDecimal> list = new ArrayList<>(List.of(amounts));
		return new Result(list, Money.round(subtotal), Money.round(subtotal.subtract(remaining)),
				Money.round(remaining));
	}

}
