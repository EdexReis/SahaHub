package com.sahahub.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sahahub.pricing.domain.PriceBreakdown.Input;

class PriceBreakdownTest {

	static BigDecimal d(String v) {
		return new BigDecimal(v);
	}

	@Test
	void orderIsPitchExtrasCouponThenStaffDiscount_regardlessOfInputOrder() {
		// Girdi sırası karışık: indirim kuponundan önce, kupon ek hizmetten önce
		List<Input> inputs = List.of(Input.staffDiscount(null, d("100")), Input.coupon(d("10"), null),
				Input.pitch(d("1000.00")), Input.extra(2, d("50")));
		PriceBreakdown.Result r = PriceBreakdown.compute(inputs);
		// ara toplam 1100 → kupon %10 = 110 → kalan 990 → personel 100 → 890
		assertThat(r.subtotal()).isEqualByComparingTo("1100");
		assertThat(r.amounts().get(1)).isEqualByComparingTo("-110");
		assertThat(r.amounts().get(0)).isEqualByComparingTo("-100");
		assertThat(r.total()).isEqualByComparingTo("890");
		assertThat(r.discounts()).isEqualByComparingTo("210");
	}

	@Test
	void percentStaffDiscountAppliesAfterCoupon() {
		PriceBreakdown.Result r = PriceBreakdown.compute(List.of(Input.pitch(d("1000")), Input.coupon(null, d("200")),
				Input.staffDiscount(d("10"), null)));
		// 1000 − 200 = 800 → %10 = 80 → 720
		assertThat(r.total()).isEqualByComparingTo("720");
	}

	@Test
	void discountsNeverMakeTotalNegative() {
		PriceBreakdown.Result r = PriceBreakdown.compute(List.of(Input.pitch(d("300")), Input.coupon(null, d("250")),
				Input.staffDiscount(null, d("500"))));
		assertThat(r.amounts().get(1)).isEqualByComparingTo("-250");
		assertThat(r.amounts().get(2)).isEqualByComparingTo("-50");
		assertThat(r.total()).isEqualByComparingTo("0");
	}

	@Test
	void percentRoundingHalfUp() {
		// 333.33 × %15 = 49.9995 → 50.00
		PriceBreakdown.Result r = PriceBreakdown.compute(List.of(Input.pitch(d("333.33")), Input.coupon(d("15"), null)));
		assertThat(r.amounts().get(1)).isEqualByComparingTo("-50.00");
		assertThat(r.total()).isEqualByComparingTo("283.33");
	}

	@Test
	void depositIsComputedFromFinalTotal() {
		DepositPolicy percent = new DepositPolicy(DepositPolicy.Type.PERCENT, d("30"));
		DepositPolicy fixed = new DepositPolicy(DepositPolicy.Type.FIXED, d("300"));
		assertThat(percent.depositFor(d("890"))).isEqualByComparingTo("267.00");
		assertThat(percent.depositFor(d("333.33"))).isEqualByComparingTo("100.00"); // 99.999 → 100.00
		assertThat(fixed.depositFor(d("250"))).isEqualByComparingTo("250"); // toplamı aşamaz
		assertThat(DepositPolicy.NONE.depositFor(d("1000"))).isEqualByComparingTo("0");
		assertThat(fixed.depositFor(d("0"))).isEqualByComparingTo("0");
	}

}
