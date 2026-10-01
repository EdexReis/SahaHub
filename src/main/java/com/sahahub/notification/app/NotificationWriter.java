package com.sahahub.notification.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.notification.domain.OutboxMessage;

/**
 * Bildirimleri yazar: uygulama içi bildirim + gönderilecek mesajlar (outbox).
 *
 * <h2>Neden transactional outbox?</h2>
 * E-postayı doğrudan iş kodunun içinden gönderseydik iki sorun olurdu: (1) işlem sonra geri alınırsa
 * müşteri gerçekleşmemiş bir rezervasyon için "onaylandı" e-postası almış olurdu; (2) e-posta sunucusu
 * yavaşsa kullanıcının isteği beklerdi. Bunun yerine mesaj, işi doğuran değişiklikle AYNI transaction'da
 * bir tabloya yazılır (MANDATORY); ayrı bir görev sonra gönderir. İşlem geri alınırsa mesaj satırı da
 * geri alınır.
 * <p>
 * Tekrar koruması: her satırın dedup_key'i tekildir ve INSERT "ON CONFLICT DO NOTHING" ile yapılır.
 * Aynı olay iki kez işlense (ör. hatırlatma görevi her 10 dakikada çalışır) ikinci mesaj oluşmaz.
 */
@Component
public class NotificationWriter {

	/** Mesajın kime gideceği ve hangi kanalları kabul ettiği. userId boşsa (misafir) uygulama içi bildirim yok. */
	public record Recipient(Long userId, String email, String phone, boolean emailEnabled, boolean shortMessageEnabled) {
	}

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final OutboxMessage.Channel shortMessageChannel;
	private final String baseUrl;

	public NotificationWriter(JdbcTemplate jdbc, Clock clock,
			@Value("${sahahub.notification.short-message-channel:SMS}") OutboxMessage.Channel shortMessageChannel,
			@Value("${sahahub.public-base-url:http://localhost:8080}") String baseUrl) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.shortMessageChannel = shortMessageChannel;
		this.baseUrl = baseUrl;
	}

	/**
	 * @param link uygulama içi göreli bağlantı (ör. /rezervasyon/ABC123); e-postada tam adrese çevrilir
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void write(Recipient to, String kind, String title, String body, String link, String dedupKey) {
		Timestamp now = Timestamp.from(Instant.now(clock));
		if (to.userId() != null) {
			jdbc.update("""
					insert into notification (user_id, kind, title, body, link, dedup_key, created_at)
					values (?, ?, ?, ?, ?, ?, ?) on conflict (dedup_key) do nothing""", to.userId(), kind, title, body,
					link, dedupKey, now);
		}
		if (to.emailEnabled() && to.email() != null) {
			outbox(OutboxMessage.Channel.EMAIL, to.email(), title, body + (link == null ? "" : "\n\n" + baseUrl + link),
					dedupKey + ":EMAIL", now);
		}
		if (to.shortMessageEnabled() && to.phone() != null) {
			outbox(shortMessageChannel, to.phone(), title, title + ". " + body, dedupKey + ":" + shortMessageChannel,
					now);
		}
	}

	private void outbox(OutboxMessage.Channel channel, String recipient, String subject, String body, String dedup,
			Timestamp now) {
		jdbc.update("""
				insert into notification_outbox (channel, recipient, subject, body, dedup_key, status, next_attempt_at,
				                                 created_at)
				values (?, ?, ?, ?, ?, 'PENDING', ?, ?) on conflict (dedup_key) do nothing""", channel.name(),
				recipient, subject, body.length() > 1000 ? body.substring(0, 1000) : body, dedup, now, now);
	}

}
