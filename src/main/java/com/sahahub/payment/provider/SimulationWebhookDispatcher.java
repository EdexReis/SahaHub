package com.sahahub.payment.provider;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Simülasyon sağlayıcısının "bildirim gönderme" tarafı. Zamanı gelen bildirimleri imzalayıp
 * uygulamanın webhook ucuna teslim eder. Gerçek sağlayıcılar gibi: teslim edilemeyen bildirim
 * bir sonraki turda yeniden denenir; bu yüzden uygulama tarafı yinelenen bildirime dayanıklı olmalıdır.
 */
@Component
public class SimulationWebhookDispatcher {

	private static final Logger log = LoggerFactory.getLogger(SimulationWebhookDispatcher.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final WebhookEndpoint endpoint;
	private final SimulationProperties properties;
	private final Clock clock;

	public SimulationWebhookDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, WebhookEndpoint endpoint,
			SimulationProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.endpoint = endpoint;
		this.properties = properties;
		this.clock = clock;
	}

	/** Zamanı gelmiş bildirimleri teslim eder; teslim edilen sayısını döner. */
	public int deliverDue() {
		List<Long> due = jdbc.queryForList("""
				select id from sim_webhook_outbox where delivered_at is null and deliver_at <= ?
				order by id limit 50""", Long.class, Timestamp.from(Instant.now(clock)));
		int delivered = 0;
		for (Long id : due) {
			Boolean ok = tx.execute(status -> deliverOne(id));
			if (Boolean.TRUE.equals(ok)) {
				delivered++;
			}
		}
		return delivered;
	}

	private boolean deliverOne(Long id) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select event_id, provider_ref, outcome from sim_webhook_outbox
				where id = ? and delivered_at is null for update skip locked""", id);
		if (rows.isEmpty()) {
			return false; // başka bir dağıtıcı aldı
		}
		Map<String, Object> row = rows.getFirst();
		String body = "{\"eventId\":\"" + row.get("event_id") + "\",\"providerRef\":\"" + row.get("provider_ref")
				+ "\",\"outcome\":\"" + row.get("outcome") + "\"}";
		try {
			endpoint.receive(body, WebhookSignature.sign(properties.webhookSecret(), body));
		}
		catch (RuntimeException ex) {
			log.warn("Simülasyon bildirimi teslim edilemedi, sonra yeniden denenecek: {}", ex.getMessage());
			return false;
		}
		jdbc.update("update sim_webhook_outbox set delivered_at = ? where id = ?", Timestamp.from(Instant.now(clock)),
				id);
		return true;
	}

}
