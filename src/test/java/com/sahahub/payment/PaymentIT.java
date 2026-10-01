package com.sahahub.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import com.sahahub.booking.app.CustomerBookingService;
import com.sahahub.booking.app.HoldExpiryService;
import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.payment.app.CashService;
import com.sahahub.payment.app.OnlinePaymentService;
import com.sahahub.payment.app.PaymentQueries;
import com.sahahub.payment.app.PaymentService;
import com.sahahub.payment.domain.CashSession;
import com.sahahub.payment.domain.Expense;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.domain.PaymentSummary;
import com.sahahub.payment.provider.SimulatedPaymentProvider;
import com.sahahub.payment.provider.SimulationProperties;
import com.sahahub.payment.provider.SimulationScenario;
import com.sahahub.payment.provider.SimulationWebhookDispatcher;
import com.sahahub.payment.provider.WebhookEndpoint;
import com.sahahub.payment.provider.WebhookSignature;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

/** Ödeme, iade, kasa ve webhook kuralları; gerçek PostgreSQL üzerinde. */
@IntegrationTest
class PaymentIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3);

	@Autowired CustomerBookingService customerBooking;
	@Autowired StaffReservationService staffBooking;
	@Autowired HoldExpiryService expiry;
	@Autowired OnlinePaymentService online;
	@Autowired PaymentService payments;
	@Autowired PaymentQueries queries;
	@Autowired CashService cash;
	@Autowired SimulatedPaymentProvider provider;
	@Autowired SimulationWebhookDispatcher dispatcher;
	@Autowired SimulationProperties simulation;
	@Autowired WebhookEndpoint webhook;
	@Autowired PaymentRepository paymentRepo;
	@Autowired ReservationRepository reservations;
	@Autowired TestData data;
	@Autowired MutableClock clock;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	static Instant at(int hour) {
		return DAY.atTime(hour, 0).atZone(TestData.IST).toInstant();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	Venue depositVenue() {
		Venue v = data.venue(); // 1000 TL/saat
		data.deposit(v, new DepositPolicy(DepositPolicy.Type.PERCENT, new BigDecimal("30")));
		return v;
	}

	/** Müşteri ödeme sayfasına gider ve senaryoyu seçer; sağlayıcı zamanı gelen bildirimleri gönderir. */
	String payOnline(AppUserPrincipal c, String code, SimulationScenario scenario) {
		String redirect = online.start(c, code, OnlinePaymentService.Option.DEPOSIT, key());
		String ref = redirect.substring(redirect.lastIndexOf('/') + 1);
		provider.complete(ref, scenario);
		dispatcher.deliverDue();
		return ref;
	}

	Reservation reservation(String code) {
		return reservations.findByCode(code).orElseThrow();
	}

	PaymentSummary summary(String code) {
		return queries.summary(reservation(code));
	}

	String staffBook(Venue v, int hour) {
		return staffBooking.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(hour, 0), 60, Channel.PHONE, null, "Misafir", null, null));
	}

	// ---------------------------------------------------------------- çevrim içi kapora

	@Nested
	class OnlineDeposit {

		@Test
		void depositIsRequiredToConfirm_andSuccessfulPaymentConfirms() {
			Venue v = depositVenue();
			AppUserPrincipal c = data.customer();
			String code = customerBooking.hold(c, v.pitch().getId(), at(21));

			assertThatThrownBy(() -> customerBooking.confirm(c, code))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("kaporayı");

			payOnline(c, code, SimulationScenario.SUCCESS);
			assertThat(reservation(code).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
			PaymentSummary s = summary(code);
			assertThat(s.deposit()).isEqualByComparingTo("300.00");
			assertThat(s.paid()).isEqualByComparingTo("300.00");
			assertThat(s.state()).isEqualTo(PaymentSummary.State.PARTIAL);
		}

		@Test
		void failedPaymentLeavesHoldOpen_andCanBeRetried() {
			Venue v = depositVenue();
			AppUserPrincipal c = data.customer();
			String code = customerBooking.hold(c, v.pitch().getId(), at(21));
			payOnline(c, code, SimulationScenario.FAIL);
			assertThat(reservation(code).getStatus()).isEqualTo(ReservationStatus.HELD);
			assertThat(summary(code).paid()).isEqualByComparingTo("0");

			payOnline(c, code, SimulationScenario.SUCCESS);
			assertThat(reservation(code).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		}

		@Test
		void sameIdempotencyKeyOpensOnlyOnePayment() {
			Venue v = depositVenue();
			AppUserPrincipal c = data.customer();
			String code = customerBooking.hold(c, v.pitch().getId(), at(21));
			String k = key();
			String first = online.start(c, code, OnlinePaymentService.Option.DEPOSIT, k);
			String second = online.start(c, code, OnlinePaymentService.Option.DEPOSIT, k);
			String third = online.start(c, code, OnlinePaymentService.Option.FULL, key()); // bekleyen ödeme var
			assertThat(second).isEqualTo(first);
			assertThat(third).isEqualTo(first);
			assertThat(paymentRepo.findByReservationIdOrderByIdAsc(reservation(code).getId())).hasSize(1);
		}

	}

	// ---------------------------------------------------------------- senaryo 8: yinelenen bildirim

	@Test
	void duplicateWebhookIsProcessedOnce() {
		Venue v = depositVenue();
		AppUserPrincipal c = data.customer();
		String code = customerBooking.hold(c, v.pitch().getId(), at(20));
		String ref = payOnline(c, code, SimulationScenario.DUPLICATE_SUCCESS);

		Long events = jdbc.queryForObject("select count(*) from payment_event where provider_ref = ?", Long.class, ref);
		Long deliveries = jdbc.queryForObject(
				"select count(*) from sim_webhook_outbox where provider_ref = ? and delivered_at is not null", Long.class,
				ref);
		assertThat(deliveries).isEqualTo(2); // sağlayıcı iki kez gönderdi
		assertThat(events).isEqualTo(1); // uygulama bir kez işledi
		assertThat(summary(code).paid()).isEqualByComparingTo("300.00");

		// Aynı gövde elle üçüncü kez gönderilse de değişmez
		String eventId = jdbc.queryForObject("select event_id from payment_event where provider_ref = ?", String.class,
				ref);
		String body = "{\"eventId\":\"" + eventId + "\",\"providerRef\":\"" + ref + "\",\"outcome\":\"SUCCEEDED\"}";
		assertThat(webhook.receive(body, WebhookSignature.sign(simulation.webhookSecret(), body)))
			.isEqualTo(WebhookEndpoint.Result.DUPLICATE);
		assertThat(summary(code).paid()).isEqualByComparingTo("300.00");
	}

	@Test
	void webhookWithWrongSignatureIsRejected() {
		String body = "{\"eventId\":\"evt_fake\",\"providerRef\":\"sim_x\",\"outcome\":\"SUCCEEDED\"}";
		assertThatThrownBy(() -> webhook.receive(body, "deadbeef")).isInstanceOf(SecurityException.class);
		assertThatThrownBy(() -> webhook.receive(body, null)).isInstanceOf(SecurityException.class);
	}

	// ---------------------------------------------------------------- senaryo 9: geç gelen ödeme

	@Test
	void paymentArrivingAfterHoldExpiredIsRefundedAutomatically() {
		Venue v = depositVenue();
		AppUserPrincipal c = data.customer();
		String code = customerBooking.hold(c, v.pitch().getId(), at(19));
		payOnline(c, code, SimulationScenario.DELAYED_SUCCESS); // bildirim 60 sn gecikecek
		assertThat(reservation(code).getStatus()).isEqualTo(ReservationStatus.HELD);

		clock.advance(Duration.ofMinutes(10)); // tutma süresi doldu
		expiry.expireDueHolds();
		assertThat(reservation(code).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		// Saat boşaldı; başka müşteri alabilir
		String other = customerBooking.hold(data.customer(), v.pitch().getId(), at(19));
		assertThat(other).isNotBlank();

		assertThat(dispatcher.deliverDue()).isEqualTo(1); // geç bildirim geldi
		Reservation r = reservation(code);
		assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED); // onaylanmadı
		List<Payment> list = paymentRepo.findByReservationIdOrderByIdAsc(r.getId());
		assertThat(list).extracting(Payment::getKind, Payment::getStatus).containsExactly(
				org.assertj.core.groups.Tuple.tuple(Payment.Kind.CHARGE, Payment.Status.SUCCEEDED),
				org.assertj.core.groups.Tuple.tuple(Payment.Kind.REFUND, Payment.Status.SUCCEEDED));
		assertThat(summary(code).paid()).isEqualByComparingTo("0");
		assertThat(summary(code).state()).isEqualTo(PaymentSummary.State.NOTHING_DUE);
	}

	@Test
	void customerCancellationWithinWindowRefundsOnlinePayment() {
		Venue v = depositVenue();
		AppUserPrincipal c = data.customer();
		String code = customerBooking.hold(c, v.pitch().getId(), at(21));
		payOnline(c, code, SimulationScenario.SUCCESS);
		customerBooking.cancel(c, code);
		assertThat(summary(code).paid()).isEqualByComparingTo("0");
	}

	@Test
	void failedRefundIsRecordedAndListedForStaff() {
		Venue v = depositVenue();
		AppUserPrincipal c = data.customer();
		String code = customerBooking.hold(c, v.pitch().getId(), at(21));
		payOnline(c, code, SimulationScenario.SUCCESS_REFUND_FAILS);
		Payment charge = paymentRepo.findByReservationIdOrderByIdAsc(reservation(code).getId()).getFirst();

		Payment refund = payments.refund(v.manager(), code, charge.getId(), new BigDecimal("100"), key(), null);
		assertThat(refund.getStatus()).isEqualTo(Payment.Status.FAILED);
		assertThat(refund.getFailureReason()).contains("reddetti");
		assertThat(summary(code).paid()).isEqualByComparingTo("300.00"); // para hâlâ işletmede
		assertThat(queries.failedRefunds(v.branch().getId())).extracting(Payment::getId).contains(refund.getId());
	}

	// ---------------------------------------------------------------- senaryo 10: kısmi ödeme ve kısmi iade

	@Nested
	class PartialPaymentsAndRefunds {

		@Test
		void partialCollectionsAndRefundsAddUp() {
			Venue v = data.venue();
			String code = staffBook(v, 20); // 1000 TL, kapora yok
			cash.open(v.reception(), v.branch().getId(), new BigDecimal("500"));

			Payment c1 = payments.collect(v.reception(), code, Payment.Method.CASH, new BigDecimal("400"), key(), null);
			assertThat(summary(code).state()).isEqualTo(PaymentSummary.State.PARTIAL);
			assertThat(summary(code).balance()).isEqualByComparingTo("600");

			assertThatThrownBy(() -> payments.collect(v.reception(), code, Payment.Method.MANUAL_POS,
					new BigDecimal("700"), key(), null))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("kalan borç");
			payments.collect(v.reception(), code, Payment.Method.MANUAL_POS, new BigDecimal("600"), key(), null);
			assertThat(summary(code).state()).isEqualTo(PaymentSummary.State.PAID);

			payments.refund(v.manager(), code, c1.getId(), new BigDecimal("150"), key(), "Kısmi iade");
			assertThat(summary(code).paid()).isEqualByComparingTo("850");
			assertThatThrownBy(() -> payments.refund(v.manager(), code, c1.getId(), new BigDecimal("300"), key(), null))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("en fazla 250");
			payments.refund(v.manager(), code, c1.getId(), new BigDecimal("250"), key(), null);
			assertThat(summary(code).paid()).isEqualByComparingTo("600");

			// Kasa: 500 + 400 − 150 − 250 = 500 (POS kasayı etkilemez)
			CashSession closed = cash.close(v.reception(), v.branch().getId(), new BigDecimal("480"), "20 eksik");
			assertThat(closed.getExpectedAmount()).isEqualByComparingTo("500");
			assertThat(closed.difference()).isEqualByComparingTo("-20");
		}

		@Test
		void sameKeyCreatesOnePayment() {
			Venue v = data.venue();
			String code = staffBook(v, 20);
			String k = key();
			Payment a = payments.collect(v.reception(), code, Payment.Method.MANUAL_POS, new BigDecimal("100"), k, null);
			Payment b = payments.collect(v.reception(), code, Payment.Method.MANUAL_POS, new BigDecimal("100"), k, null);
			assertThat(b.getId()).isEqualTo(a.getId());
			assertThat(summary(code).paid()).isEqualByComparingTo("100");
		}

		@Test
		void concurrentRefundsCannotExceedTheCharge() throws Exception {
			Venue v = data.venue();
			String code = staffBook(v, 20);
			Payment charge = payments.collect(v.reception(), code, Payment.Method.MANUAL_POS, new BigDecimal("400"),
					key(), null);
			List<Throwable> errors = runConcurrently(
					() -> payments.refund(v.manager(), code, charge.getId(), new BigDecimal("300"), key(), null),
					() -> payments.refund(v.manager(), code, charge.getId(), new BigDecimal("300"), key(), null));
			assertThat(errors).filteredOn(e -> e == null).hasSize(1);
			assertThat(errors).filteredOn(e -> e instanceof BusinessRuleException).hasSize(1);
			assertThat(summary(code).paid()).isEqualByComparingTo("100");
		}

		@Test
		void cashRequiresOpenRegister_andReversalIsOneTime() {
			Venue v = data.venue();
			String code = staffBook(v, 20);
			assertThatThrownBy(() -> payments.collect(v.reception(), code, Payment.Method.CASH, new BigDecimal("100"),
					key(), null))
				.hasMessageContaining("kasayı açın");
			cash.open(v.reception(), v.branch().getId(), BigDecimal.ZERO);
			assertThatThrownBy(() -> cash.open(v.reception(), v.branch().getId(), BigDecimal.ZERO))
				.hasMessageContaining("zaten açık");

			Payment wrong = payments.collect(v.reception(), code, Payment.Method.CASH, new BigDecimal("1000"), key(),
					null);
			payments.reverse(v.reception(), code, wrong.getId(), "Tutar yanlış girildi", key());
			assertThat(summary(code).paid()).isEqualByComparingTo("0");
			assertThatThrownBy(() -> payments.reverse(v.reception(), code, wrong.getId(), "tekrar", key()))
				.isInstanceOf(BusinessRuleException.class);
			// Hareketler silinmedi: tahsilat ve ters kaydı birlikte duruyor
			assertThat(paymentRepo.findByReservationIdOrderByIdAsc(reservation(code).getId())).hasSize(2);
		}

		@Test
		void bankTransferCountsOnlyAfterVerification() {
			Venue v = data.venue();
			AppUserPrincipal c = data.customer();
			String code = customerBooking.hold(c, v.pitch().getId(), at(20));
			customerBooking.confirm(c, code); // kapora yok
			Payment t = payments.notifyTransfer(c, code, new BigDecimal("400"), "Ali Veli", key());
			assertThat(summary(code).paid()).isEqualByComparingTo("0");
			assertThat(summary(code).pending()).isEqualByComparingTo("400");
			payments.verifyTransfer(v.reception(), t.getId(), true, null);
			assertThat(summary(code).paid()).isEqualByComparingTo("400");

			Payment t2 = payments.notifyTransfer(c, code, new BigDecimal("100"), "Ali Veli", key());
			assertThatThrownBy(() -> payments.verifyTransfer(v.reception(), t2.getId(), false, " "))
				.hasMessageContaining("gerekçe");
			payments.verifyTransfer(v.reception(), t2.getId(), false, "Hesaba geçmedi");
			assertThat(summary(code).paid()).isEqualByComparingTo("400");
		}

		@Test
		void cashExpenseReducesExpected_andExpenseReversalRestoresIt() {
			Venue v = data.venue();
			cash.open(v.manager(), v.branch().getId(), new BigDecimal("1000"));
			cash.addExpense(v.manager(), v.branch().getId(), Expense.Category.SUPPLIES, new BigDecimal("200"), "Top",
					true);
			var view = cash.view(v.manager(), v.branch().getId());
			assertThat(view.open().expected()).isEqualByComparingTo("800");
			cash.reverseExpense(v.manager(), view.expenses().getFirst().id());
			assertThat(cash.view(v.manager(), v.branch().getId()).open().expected()).isEqualByComparingTo("1000");
		}

	}

	// ---------------------------------------------------------------- yetki

	@Test
	void receptionCannotRefundOrRecordExpenses() {
		Venue v = data.venue();
		String code = staffBook(v, 20);
		Payment charge = payments.collect(v.reception(), code, Payment.Method.MANUAL_POS, new BigDecimal("100"), key(),
				null);
		assertThatThrownBy(() -> payments.refund(v.reception(), code, charge.getId(), new BigDecimal("50"), key(), null))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> cash.addExpense(v.reception(), v.branch().getId(), Expense.Category.OTHER,
				new BigDecimal("10"), "x", false))
			.isInstanceOf(AccessDeniedException.class);
		Venue other = data.venue();
		assertThatThrownBy(() -> payments.collect(other.owner(), code, Payment.Method.MANUAL_POS, new BigDecimal("10"),
				key(), null))
			.isInstanceOf(AccessDeniedException.class);
	}

	@SafeVarargs
	private static List<Throwable> runConcurrently(Callable<?>... tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
		CountDownLatch gate = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Callable<?> t : tasks) {
				futures.add(pool.submit(() -> {
					gate.await();
					return t.call();
				}));
			}
			gate.countDown();
			List<Throwable> errors = new ArrayList<>();
			for (Future<?> f : futures) {
				try {
					f.get();
					errors.add(null);
				}
				catch (ExecutionException ex) {
					errors.add(ex.getCause());
				}
			}
			return errors;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
