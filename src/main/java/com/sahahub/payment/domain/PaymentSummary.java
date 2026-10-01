package com.sahahub.payment.domain;

import java.math.BigDecimal;
import java.util.List;

import com.sahahub.shared.domain.Money;

/**
 * Bir rezervasyonun ödeme durumu. SAKLANMAZ; her seferinde hareketlerden hesaplanır.
 * Böylece rezervasyon durumu ("Onaylandı") ile ödeme durumu ("Kapora bekleniyor") ayrı kalır
 * ve hareketlerle çelişen bir "ödendi" bayrağı oluşamaz.
 *
 * @param due     müşterinin ödemesi gereken tutar: iptal/süresi dolmuşsa 0, aksi hâlde rezervasyon toplamı
 * @param deposit kapora (yoksa 0)
 * @param paid    başarılı tahsilatlar − başarılı iadeler − ters kayıtlar
 * @param pending sonucu bekleyen tahsilatlar (havale bildirimi, çevrim içi ödeme)
 */
public record PaymentSummary(BigDecimal due, BigDecimal deposit, BigDecimal paid, BigDecimal pending,
		String currency) {

	public enum State {

		NOTHING_DUE("Ödeme yok"),
		UNPAID("Ödenmedi"),
		DEPOSIT_DUE("Kapora bekleniyor"),
		PARTIAL("Kısmi ödendi"),
		PAID("Ödendi"),
		REFUND_DUE("İade edilecek");

		private final String label;

		State(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public static PaymentSummary of(BigDecimal due, BigDecimal deposit, List<Payment> payments, String currency) {
		BigDecimal paid = BigDecimal.ZERO;
		BigDecimal pending = BigDecimal.ZERO;
		for (Payment p : payments) {
			paid = paid.add(p.signedAmount());
			if (p.isPending() && p.getKind() == Payment.Kind.CHARGE) {
				pending = pending.add(p.getAmount());
			}
		}
		return new PaymentSummary(Money.round(due), Money.round(deposit), Money.round(paid), Money.round(pending),
				currency);
	}

	/** Kalan borç (negatifse müşteriye fazla ödeme var). */
	public BigDecimal balance() {
		return due.subtract(paid);
	}

	/** Kapora için daha ödenmesi gereken (0 veya pozitif). */
	public BigDecimal depositRemaining() {
		return deposit.subtract(paid).max(BigDecimal.ZERO);
	}

	/** İade edilebilecek en fazla tutar: fazla ödeme ya da (iptal varsa) ödenenin tamamı. */
	public BigDecimal overpaid() {
		return paid.subtract(due).max(BigDecimal.ZERO);
	}

	public State state() {
		if (paid.compareTo(due) > 0) {
			return State.REFUND_DUE;
		}
		if (due.signum() == 0) {
			return State.NOTHING_DUE;
		}
		if (paid.compareTo(due) == 0) {
			return State.PAID;
		}
		if (deposit.signum() > 0 && paid.compareTo(deposit) < 0) {
			return State.DEPOSIT_DUE;
		}
		return paid.signum() > 0 ? State.PARTIAL : State.UNPAID;
	}

}
