package com.sahahub.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.ReservationPricingService;
import com.sahahub.booking.app.ReservationView;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.CouponRepository;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.pricing.domain.ExtraService;
import com.sahahub.pricing.domain.ExtraServiceRepository;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

@IntegrationTest
class ReservationPricingIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired ReservationPricingService pricing;
	@Autowired CustomerBookingService customer;
	@Autowired StaffReservationService staff;
	@Autowired CouponRepository coupons;
	@Autowired ExtraServiceRepository extras;
	@Autowired TestData data;
	@Autowired MutableClock clock;
	@Autowired TransactionTemplate tx;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	static Instant at(int hour) {
		return DAY.atTime(hour, 0).atZone(TestData.IST).toInstant();
	}

	@Test
	void extrasCouponAndStaffDiscountFollowTheDocumentedOrder() {
		Venue v = data.venue(); // 1000 TL
		ExtraService referee = data.extra(v, "Hakem", "400");
		data.coupon(v, "YUZDE10", Coupon.Kind.PERCENT, "10", 5);
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(20));

		pricing.addExtra(c, code, referee.getId(), 1); // 1400
		pricing.applyCoupon(c, code, "yuzde10"); // büyük/küçük harf duyarsız: −140 → 1260
		assertThat(customer.view(c, code).total()).isEqualByComparingTo("1260.00");

		customer.confirm(c, code);
		pricing.applyStaffDiscount(v.manager(), code, null, new BigDecimal("60"), "Düzenli müşteri"); // 1200
		ReservationView r = staff.view(v.owner(), code);
		assertThat(r.total()).isEqualByComparingTo("1200.00");
		assertThat(r.lines()).extracting(ReservationView.Line::label)
			.containsExactly("Standart ücret", "Hakem", "Kupon YUZDE10", "Personel indirimi");
	}

	@Test
	void percentCouponIsRecomputedWhenExtrasChange() {
		Venue v = data.venue();
		ExtraService drinks = data.extra(v, "İçecek", "200");
		data.coupon(v, "ON", Coupon.Kind.PERCENT, "10", 5);
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(20));
		pricing.applyCoupon(c, code, "ON"); // 1000 − 100
		pricing.addExtra(c, code, drinks.getId(), 1); // 1200 − 120
		assertThat(customer.view(c, code).total()).isEqualByComparingTo("1080.00");
		Long extraLine = customer.view(c, code).lines().stream().filter(l -> l.label().equals("İçecek")).findFirst()
			.orElseThrow().id();
		pricing.voidLine(c, code, extraLine);
		assertThat(customer.view(c, code).total()).isEqualByComparingTo("900.00");
	}

	@Test
	void couponLastUseCannotBeTakenTwiceConcurrently() throws Exception {
		Venue v = data.venue();
		Coupon once = data.coupon(v, "TEK", Coupon.Kind.FIXED, "200", 1);
		AppUserPrincipal a = data.customer();
		AppUserPrincipal b = data.customer();
		String codeA = customer.hold(a, v.pitch().getId(), at(19));
		String codeB = customer.hold(b, v.pitch().getId(), at(20));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch gate = new CountDownLatch(1);
		List<Future<?>> fs = new ArrayList<>();
		fs.add(pool.submit(() -> { gate.await(); pricing.applyCoupon(a, codeA, "TEK"); return null; }));
		fs.add(pool.submit(() -> { gate.await(); pricing.applyCoupon(b, codeB, "TEK"); return null; }));
		gate.countDown();
		int ok = 0;
		int rejected = 0;
		for (Future<?> f : fs) {
			try {
				f.get();
				ok++;
			}
			catch (ExecutionException ex) {
				assertThat(ex.getCause()).isInstanceOf(BusinessRuleException.class).hasMessageContaining("hakkı doldu");
				rejected++;
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(rejected).isEqualTo(1);
		assertThat(coupons.findById(once.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
	}

	@Test
	void cancellingReleasesTheCouponUse() {
		Venue v = data.venue();
		Coupon once = data.coupon(v, "BIR", Coupon.Kind.FIXED, "100", 1);
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(19));
		pricing.applyCoupon(c, code, "BIR");
		customer.cancel(c, code);
		assertThat(coupons.findById(once.getId()).orElseThrow().getUsedCount()).isZero();
		String again = customer.hold(c, v.pitch().getId(), at(20));
		pricing.applyCoupon(c, again, "BIR");
		assertThat(customer.view(c, again).total()).isEqualByComparingTo("900.00");
	}

	@Test
	void secondCouponOnSameReservationIsRejected() {
		Venue v = data.venue();
		data.coupon(v, "A1", Coupon.Kind.FIXED, "100", 5);
		data.coupon(v, "B2", Coupon.Kind.FIXED, "100", 5);
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(19));
		pricing.applyCoupon(c, code, "A1");
		assertThatThrownBy(() -> pricing.applyCoupon(c, code, "B2")).hasMessageContaining("zaten bir kupon");
		String fresh = customer.hold(c, v.pitch().getId(), at(21));
		assertThatThrownBy(() -> pricing.applyCoupon(c, fresh, "YOK")).hasMessageContaining("geçersiz");
	}

	@Test
	void staffDiscountNeedsReasonAndPermission() {
		Venue v = data.venue();
		String code = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Misafir", null, null));
		assertThatThrownBy(() -> pricing.applyStaffDiscount(v.reception(), code, new BigDecimal("10"), null, "x"))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> pricing.applyStaffDiscount(v.manager(), code, new BigDecimal("10"), null, " "))
			.hasMessageContaining("gerekçe");
		assertThatThrownBy(() -> pricing.applyStaffDiscount(v.manager(), code, new BigDecimal("10"),
				new BigDecimal("50"), "iki alan"))
			.hasMessageContaining("yalnızca birini");
	}

	@Test
	void customerCannotChangeConfirmedOrOthersReservation() {
		Venue v = data.venue();
		ExtraService e = data.extra(v, "Krampon", "50");
		AppUserPrincipal c = data.customer();
		String code = customer.hold(c, v.pitch().getId(), at(19));
		assertThatThrownBy(() -> pricing.addExtra(data.customer(), code, e.getId(), 1))
			.isInstanceOf(NotFoundException.class);
		customer.confirm(c, code);
		assertThatThrownBy(() -> pricing.addExtra(c, code, e.getId(), 1))
			.isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("şubeyle iletişime");
	}

	@Test
	void depositPolicyAndExtraPriceAreSnapshotted() {
		Venue v = data.venue();
		data.deposit(v, new DepositPolicy(DepositPolicy.Type.FIXED, new BigDecimal("300")));
		ExtraService e = data.extra(v, "Hakem", "400");
		String code = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(20, 0), 60, Channel.PHONE, null, "Misafir", null, null));
		pricing.addExtra(v.reception(), code, e.getId(), 1);

		data.deposit(v, new DepositPolicy(DepositPolicy.Type.PERCENT, new BigDecimal("50")));
		tx.executeWithoutResult(s -> extras.findById(e.getId()).orElseThrow().deactivate());

		ReservationView r = staff.view(v.owner(), code);
		assertThat(r.deposit()).isEqualByComparingTo("300.00"); // eski kural
		assertThat(r.total()).isEqualByComparingTo("1400.00"); // eklenen hizmet fiyatı kopya
	}

}
