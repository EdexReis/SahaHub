package com.sahahub.notification.channel;

import com.sahahub.notification.domain.OutboxMessage;

/**
 * Bir dış mesaj kanalı (e-posta, SMS, WhatsApp). Gönderim başarısızsa istisna fırlatır; gönderici
 * mesajı daha sonra yeniden dener.
 */
public interface MessageChannel {

	OutboxMessage.Channel channel();

	/** Sağlayıcı adı; outbox satırına yazılır ("SMTP", "DEMO"). */
	String provider();

	void send(OutboxMessage message) throws Exception;

}
