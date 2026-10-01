package com.sahahub.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sahahub.payment.domain.PaymentSummary.State;

class PaymentSummaryTest {

	static final Instant NOW = Instant.parse("2026-03-03T10:00:00Z");
	static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

	static Payment cash(String amount) {
		return Payment.collected(1L, 1L, 1L, Payment.Method.CASH, new BigDecimal(amount), "TRY", "k" + SEQ.incrementAndGet(),
				1L, null, 1L, NOW);
	}

	static PaymentSummary summary(String due, String deposit, List<Payment> ps) {
		return PaymentSummary.of(new BigDecimal(due), new BigDecimal(deposit), ps, "TRY");
	}

	@Test
	void statesFollowPaidAmount() {
		assertThat(summary("1000", "300", List.of()).state()).isEqualTo(State.DEPOSIT_DUE);
		assertThat(summary("1000", "0", List.of()).state()).isEqualTo(State.UNPAID);
		assertThat(summary("1000", "300", List.of(cash("200"))).state()).isEqualTo(State.DEPOSIT_DUE);
		assertThat(summary("1000", "300", List.of(cash("300"))).state()).isEqualTo(State.PARTIAL);
		assertThat(summary("1000", "300", List.of(cash("300"), cash("700"))).state()).isEqualTo(State.PAID);
		assertThat(summary("1000", "300", List.of(cash("1100"))).state()).isEqualTo(State.REFUND_DUE);
		assertThat(summary("0", "0", List.of(cash("300"))).overpaid()).isEqualByComparingTo("300");
		assertThat(summary("0", "0", List.of()).state()).isEqualTo(State.NOTHING_DUE);
	}

	@Test
	void pendingAndFailedPaymentsDoNotCountAsPaid() {
		Payment pending = Payment.pendingCharge(1L, 1L, 1L, Payment.Method.BANK_TRANSFER, new BigDecimal("500"), "TRY",
				"t1", "Ali", null, 1L, NOW);
		Payment failed = Payment.pendingCharge(1L, 1L, 1L, Payment.Method.ONLINE_SIM, new BigDecimal("300"), "TRY",
				"t2", null, null, 1L, NOW);
		failed.fail("red", NOW);
		PaymentSummary s = summary("1000", "300", List.of(pending, failed));
		assertThat(s.paid()).isEqualByComparingTo("0");
		assertThat(s.pending()).isEqualByComparingTo("500");
		assertThat(s.state()).isEqualTo(State.DEPOSIT_DUE);
	}

	@Test
	void settledPaymentCannotChangeAgain() {
		Payment p = Payment.pendingCharge(1L, 1L, 1L, Payment.Method.ONLINE_SIM, new BigDecimal("300"), "TRY", "x",
				null, null, 1L, NOW);
		p.succeed(NOW);
		assertThatThrownBy(() -> p.fail("geç", NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> p.succeed(NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void amountMustBePositive() {
		assertThatThrownBy(() -> cash("0")).hasMessageContaining("sıfırdan büyük");
	}

}
