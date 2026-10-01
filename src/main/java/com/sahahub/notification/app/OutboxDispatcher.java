package com.sahahub.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.notification.channel.MessageChannel;
import com.sahahub.notification.domain.OutboxMessage;
import com.sahahub.notification.domain.OutboxRepository;

/**
 * Bekleyen outbox mesajlarını gönderir.
 * <p>
 * Satırlar FOR UPDATE SKIP LOCKED ile kilitlenir; iki gönderici aynı anda çalışsa da bir mesajı yalnızca
 * biri alır. Gönderim başarılıysa SENT, değilse artan beklemeyle tekrar (en fazla 5 deneme).
 * <p>
 * Bilinen sınır: SMTP gönderimi başarılı olup transaction commit'ten önce düşerse mesaj tekrar
 * gönderilebilir ("en az bir kez"). Bu, e-posta için kabul edilebilir; ödeme gibi işlemler bu yolu
 * kullanmaz.
 */
@Service
public class OutboxDispatcher {

	public static final int BATCH_SIZE = 20;

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

	private final OutboxRepository outbox;
	private final Map<OutboxMessage.Channel, MessageChannel> channels = new EnumMap<>(OutboxMessage.Channel.class);
	private final Clock clock;

	public OutboxDispatcher(OutboxRepository outbox, List<MessageChannel> channelList, Clock clock) {
		this.outbox = outbox;
		this.clock = clock;
		channelList.forEach(c -> channels.put(c.channel(), c));
	}

	/** @return bu turda işlenen (gönderilen veya ertelenen) mesaj sayısı */
	@Transactional
	public int dispatchDue() {
		Instant now = Instant.now(clock);
		List<Long> ids = outbox.lockDue(now, BATCH_SIZE);
		for (OutboxMessage m : outbox.findAllById(ids)) {
			MessageChannel ch = channels.get(m.getChannel());
			try {
				if (ch == null) {
					throw new IllegalStateException("Kanal tanımlı değil: " + m.getChannel());
				}
				ch.send(m);
				m.markSent(ch.provider(), now);
			}
			catch (Exception e) {
				log.warn("Mesaj gönderilemedi id={} kanal={} deneme={}: {}", m.getId(), m.getChannel(),
						m.getAttempts() + 1, e.toString());
				m.markFailedAttempt(e.getClass().getSimpleName() + ": " + e.getMessage(), now);
			}
		}
		return ids.size();
	}

}
