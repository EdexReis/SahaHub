package com.sahahub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.booking.app.StaffReservationService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.notification.app.NotificationService;
import com.sahahub.notification.app.OutboxDispatcher;
import com.sahahub.notification.app.ReservationNotifications;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;
import com.sahahub.support.TestData.Venue;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bildirimler: outbox aynı transaction'da yazılır, geri alınan işlem mesaj üretmez, gönderici her
 * mesajı bir kez gönderir (gerçek SMTP: Mailpit konteyneri), hatırlatmalar tekrarlanmaz.
 */
@IntegrationTest
class NotificationIT {

	static final LocalDate DAY = LocalDate.of(2026, 3, 3); // Salı

	@Autowired
	StaffReservationService staff;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	OutboxDispatcher dispatcher;

	@Autowired
	ReservationNotifications reminders;

	@Autowired
	NotificationService inbox;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Value("${test.mailpit.api}")
	String mailpitApi;

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	String bookFor(Venue v, AppUserPrincipal c, int hour) {
		return staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(hour, 0), 60, Channel.PHONE, c.email(), null, null, null));
	}

	Long idOf(String code) {
		return reservations.findByCode(code).orElseThrow().getId();
	}

	List<Map<String, Object>> outbox(String dedupPrefix) {
		return jdbc.queryForList("select channel, recipient, status, provider, attempts from notification_outbox "
				+ "where dedup_key like ? order by id", dedupPrefix + "%");
	}

	int mailsTo(String address) throws Exception {
		String q = URLEncoder.encode("to:" + address, StandardCharsets.UTF_8);
		HttpResponse<String> res = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(mailpitApi + "/api/v1/search?query=" + q)).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(res.statusCode()).isEqualTo(200);
		return JsonMapper.builder().build().readTree(res.body()).get("messages_count").asInt();
	}

	void dispatchAll() {
		while (dispatcher.dispatchDue() == OutboxDispatcher.BATCH_SIZE) {
			// grup doluysa devam
		}
	}

	@Test
	void confirmationWritesInAppNotificationAndEmailInSameTransaction() {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		String code = bookFor(v, c, 20);
		Long id = idOf(code);

		assertThat(inbox.unreadCount(c.id())).isEqualTo(1);
		assertThat(inbox.inbox(c.id(), 0).getContent().getFirst().getLink()).isEqualTo("/rezervasyon/" + code);
		List<Map<String, Object>> rows = outbox("confirmed:" + id + ":");
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst()).containsEntry("channel", "EMAIL").containsEntry("recipient", c.email())
			.containsEntry("status", "PENDING");

		assertThat(inbox.markAllRead(c.id())).isEqualTo(1);
		assertThat(inbox.unreadCount(c.id())).isZero();
	}

	@Test
	void rolledBackReservationProducesNoMessage() {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		tx.executeWithoutResult(status -> {
			bookFor(v, c, 20);
			status.setRollbackOnly();
		});
		assertThat(jdbc.queryForObject("select count(*) from notification where user_id = ?", Integer.class, c.id()))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from notification_outbox where recipient = ?", Integer.class,
				c.email())).isZero();
	}

	@Test
	void dispatcherSendsEachEmailOnce_viaRealSmtp() throws Exception {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		Long id = idOf(bookFor(v, c, 20));

		dispatchAll();
		dispatchAll(); // ikinci çalıştırma (ör. görev tekrar tetiklendi) yeni gönderim yapmaz

		assertThat(outbox("confirmed:" + id + ":")).singleElement()
			.satisfies(r -> assertThat(r).containsEntry("status", "SENT").containsEntry("provider", "SMTP")
				.containsEntry("attempts", 1));
		assertThat(mailsTo(c.email())).isEqualTo(1);
	}

	@Test
	void guestWithPhoneGetsDemoSms_neverSentAnywhere() {
		Venue v = data.venue();
		String code = staff.create(v.reception(), v.branch().getId(), new StaffReservationService.CreateCommand(
				v.pitch().getId(), DAY.atTime(21, 0), 60, Channel.PHONE, null, "Misafir", "0555 123 45 67", null));
		Long id = idOf(code);
		dispatchAll();
		assertThat(outbox("confirmed:" + id + ":")).singleElement()
			.satisfies(r -> assertThat(r).containsEntry("channel", "SMS").containsEntry("recipient", "0555 123 45 67")
				.containsEntry("status", "SENT").containsEntry("provider", "DEMO"));
	}

	@Test
	void preferencesControlChannels() {
		Venue v = data.venue();
		AppUserPrincipal c = data.customer();
		inbox.updatePreferences(c.id(), false, true, "0555 765 43 21");
		Long id = idOf(bookFor(v, c, 20));
		assertThat(outbox("confirmed:" + id + ":")).singleElement()
			.satisfies(r -> assertThat(r).containsEntry("channel", "SMS").containsEntry("recipient", "0555 765 43 21"));
		assertThat(inbox.unreadCount(c.id())).isEqualTo(1); // uygulama içi bildirim her zaman
	}

	@Test
	void remindersAreCreatedOncePerReservation() {
		Venue v = data.venue();
		data.deposit(v, new DepositPolicy(DepositPolicy.Type.PERCENT, new BigDecimal("30")));
		AppUserPrincipal c = data.customer();
		Long id = idOf(bookFor(v, c, 20)); // Salı 20:00; şimdi Pazartesi 09:00 (35 saat var)

		reminders.enqueueReminders();
		reminders.enqueueReminders();
		assertThat(kinds(c)).containsExactlyInAnyOrder("RESERVATION_CONFIRMED", "PAYMENT_REMINDER");

		clock.advance(Duration.ofHours(12)); // maça 23 saat
		reminders.enqueueReminders();
		reminders.enqueueReminders();
		assertThat(kinds(c)).containsExactlyInAnyOrder("RESERVATION_CONFIRMED", "PAYMENT_REMINDER", "MATCH_REMINDER");
		assertThat(outbox("reminder:" + id + ":")).hasSize(1);
		assertThat(outbox("deposit-reminder:" + id + ":")).hasSize(1);
	}

	List<String> kinds(AppUserPrincipal c) {
		return jdbc.queryForList("select kind from notification where user_id = ?", String.class, c.id());
	}

}
