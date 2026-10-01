package com.sahahub.payment.provider;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * DEMO ödeme sağlayıcısı. Gerçek para çekmez, kart bilgisi almaz.
 * <p>
 * Dış bir sağlayıcı gibi davranır: kendi kayıtlarını (sim_charge) tutar ve sonucu uygulamaya
 * imzalı webhook ile bildirir (sim_webhook_outbox → SimulationWebhookDispatcher). Bu sayede
 * gecikmeli, yinelenen ve tutma süresi bittikten sonra gelen bildirimler gerçekçi biçimde denenir.
 * Uygulama kodu bu sınıfın tablolarına doğrudan erişmez.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider {

	public static final String NAME = "SIM";

	private final JdbcTemplate jdbc;
	private final SimulationProperties properties;
	private final Clock clock;

	public SimulatedPaymentProvider(JdbcTemplate jdbc, SimulationProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public ChargeSession createCharge(String idempotencyKey, BigDecimal amount, String currency,
			String description) {
		// Aynı anahtar → aynı referans (sağlayıcı tarafı idempotency)
		String ref = "sim_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString().replace("-", "")
			.substring(0, 20);
		jdbc.update("""
				insert into sim_charge (provider_ref, amount, status, created_at) values (?, ?, 'CREATED', ?)
				on conflict (provider_ref) do nothing""", ref, amount, Timestamp.from(Instant.now(clock)));
		return new ChargeSession(ref, "/odeme-saglayici/simulasyon/" + ref);
	}

	/**
	 * Müşterinin simülasyon sayfasında seçtiği sonuç. Sağlayıcının kendi kaydını günceller ve
	 * bildirim(ler)i kuyruğa koyar. Aynı ödeme ikinci kez sonuçlandırılamaz.
	 */
	@Transactional
	public void complete(String providerRef, SimulationScenario scenario) {
		Instant now = Instant.now(clock);
		boolean success = scenario != SimulationScenario.FAIL;
		int updated = jdbc.update("""
				update sim_charge set status = ?, refund_fails = ? where provider_ref = ? and status = 'CREATED'""",
				success ? "SUCCEEDED" : "FAILED", scenario == SimulationScenario.SUCCESS_REFUND_FAILS, providerRef);
		if (updated == 0) {
			throw new BusinessRuleException("Bu ödeme zaten sonuçlandı.");
		}
		String outcome = success ? "SUCCEEDED" : "FAILED";
		String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		Instant deliverAt = scenario == SimulationScenario.DELAYED_SUCCESS ? now.plus(properties.delay()) : now;
		enqueue(eventId, providerRef, outcome, deliverAt);
		if (scenario == SimulationScenario.DUPLICATE_SUCCESS) {
			enqueue(eventId, providerRef, outcome, deliverAt); // aynı olay kimliğiyle ikinci bildirim
		}
	}

	@Override
	@Transactional
	public RefundOutcome refund(String providerRef, BigDecimal amount, String idempotencyKey) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select amount, status, refund_fails, refunded from sim_charge where provider_ref = ? for update",
				providerRef);
		if (rows.isEmpty()) {
			return RefundOutcome.failed("Sağlayıcıda ödeme bulunamadı.");
		}
		Map<String, Object> row = rows.getFirst();
		if (!"SUCCEEDED".equals(row.get("status"))) {
			return RefundOutcome.failed("Başarılı olmayan ödeme iade edilemez.");
		}
		if (Boolean.TRUE.equals(row.get("refund_fails"))) {
			return RefundOutcome.failed("Sağlayıcı iadeyi reddetti (simülasyon senaryosu).");
		}
		BigDecimal charged = (BigDecimal) row.get("amount");
		BigDecimal refunded = (BigDecimal) row.get("refunded");
		if (refunded.add(amount).compareTo(charged) > 0) {
			return RefundOutcome.failed("İade tutarı ödemeyi aşıyor.");
		}
		jdbc.update("update sim_charge set refunded = refunded + ? where provider_ref = ?", amount, providerRef);
		return RefundOutcome.ok();
	}

	/** Simülasyon sayfasında gösterilecek tutar ve durum. */
	public Map<String, Object> charge(String providerRef) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select provider_ref, amount, status from sim_charge where provider_ref = ?", providerRef);
		if (rows.isEmpty()) {
			throw new NotFoundException("Ödeme");
		}
		return rows.getFirst();
	}

	private void enqueue(String eventId, String providerRef, String outcome, Instant deliverAt) {
		jdbc.update("insert into sim_webhook_outbox (event_id, provider_ref, outcome, deliver_at) values (?, ?, ?, ?)",
				eventId, providerRef, outcome, Timestamp.from(deliverAt));
	}

}
