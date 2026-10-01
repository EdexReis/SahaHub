package com.sahahub.payment.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.HoldExpiryService;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.provider.PaymentProvider;
import com.sahahub.payment.provider.SimulationProperties;
import com.sahahub.payment.provider.WebhookEndpoint;
import com.sahahub.payment.provider.WebhookSignature;
import com.sahahub.shared.audit.AuditService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ödeme sağlayıcısının sonuç bildirimlerini işler.
 *
 * <h2>Güvenceler</h2>
 * <ol>
 * <li><b>İmza</b>: HMAC tutmayan bildirim reddedilir (sahte "ödendi" bildirimi).</li>
 * <li><b>Tekrar koruması</b>: olay kimliği payment_event tablosuna "ON CONFLICT DO NOTHING" ile yazılır;
 * aynı olay ikinci kez gelirse hiçbir şey değişmez.</li>
 * <li><b>Tek sonuç</b>: ödeme hareketi kilitlenir; yalnızca PENDING ise sonuçlandırılır.</li>
 * <li><b>Geç gelen ödeme</b>: tutma süresi dolmuş veya iptal edilmiş rezervasyon için başarılı ödeme
 * gelirse para alınmış sayılır, rezervasyon onaylanmaz ve commit sonrası otomatik tam iade başlar.</li>
 * </ol>
 */
@Service
public class PaymentWebhookService implements WebhookEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

	private final JdbcTemplate jdbc;
	private final JsonMapper json;
	private final PaymentRepository payments;
	private final ReservationRepository reservations;
	private final HoldExpiryService expiry;
	private final PaymentProvider provider;
	private final SimulationProperties simulation;
	private final ApplicationEventPublisher events;
	private final AuditService audit;
	private final Clock clock;

	public PaymentWebhookService(JdbcTemplate jdbc, JsonMapper json, PaymentRepository payments,
			ReservationRepository reservations, HoldExpiryService expiry, PaymentProvider provider,
			SimulationProperties simulation, ApplicationEventPublisher events, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.payments = payments;
		this.reservations = reservations;
		this.expiry = expiry;
		this.provider = provider;
		this.simulation = simulation;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Result receive(String rawBody, String signature) {
		if (!WebhookSignature.verify(simulation.webhookSecret(), rawBody, signature)) {
			throw new SecurityException("Geçersiz webhook imzası");
		}
		JsonNode node = json.readTree(rawBody);
		String eventId = node.path("eventId").asString();
		String providerRef = node.path("providerRef").asString();
		String outcome = node.path("outcome").asString();
		Instant now = Instant.now(clock);

		int inserted = jdbc.update("""
				insert into payment_event (provider, event_id, provider_ref, outcome, received_at)
				values (?, ?, ?, ?, ?) on conflict (provider, event_id) do nothing""", provider.name(), eventId,
				providerRef, outcome, Timestamp.from(now));
		if (inserted == 0) {
			log.info("Yinelenen ödeme bildirimi yok sayıldı: {}", eventId);
			return Result.DUPLICATE;
		}

		Optional<Payment> found = payments.findByProviderRefForUpdate(providerRef);
		if (found.isEmpty() || !found.get().isPending()) {
			return Result.IGNORED;
		}
		Payment payment = found.get();
		Reservation r = reservations.findByIdForUpdate(payment.getReservationId()).orElseThrow();

		if (!"SUCCEEDED".equals(outcome)) {
			payment.fail("Sağlayıcı ödemeyi reddetti.", now);
			return Result.PROCESSED;
		}
		payment.succeed(now);
		if (r.getStatus() == ReservationStatus.HELD && r.isHoldExpired(now)) {
			expiry.expire(r, now); // görev henüz işlememiş olabilir
		}
		if (r.getStatus() == ReservationStatus.HELD) {
			r.confirm(now);
		}
		else if (r.getStatus() == ReservationStatus.CANCELLED || r.getStatus() == ReservationStatus.EXPIRED) {
			audit.record(null, r.getBusinessId(), "LATE_PAYMENT_RECEIVED", "Payment", payment.getId(),
					"reservation=" + r.getCode() + ", status=" + r.getStatus());
			events.publishEvent(new LatePaymentReceived(payment.getId()));
		}
		return Result.PROCESSED;
	}

}
