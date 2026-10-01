package com.sahahub.notification.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sahahub.notification.domain.OutboxMessage;

/**
 * SMS ve WhatsApp DEMO kanallarıdır: hiçbir mesaj gerçekten gönderilmez. Mesaj outbox'ta "DEMO"
 * sağlayıcısıyla gönderildi olarak işaretlenir ve yönetici "Demo mesaj kutusu" ekranında görünür.
 * Gerçek bir SMS/WhatsApp sağlayıcısı ücretlidir ve gerçek kişilere ulaşır; bu yüzden ayrı bir adımdır.
 */
@Configuration
class DemoShortMessageChannels {

	@Bean
	MessageChannel demoSmsChannel() {
		return demo(OutboxMessage.Channel.SMS);
	}

	@Bean
	MessageChannel demoWhatsAppChannel() {
		return demo(OutboxMessage.Channel.WHATSAPP);
	}

	private static MessageChannel demo(OutboxMessage.Channel ch) {
		return new MessageChannel() {

			@Override
			public OutboxMessage.Channel channel() {
				return ch;
			}

			@Override
			public String provider() {
				return "DEMO";
			}

			@Override
			public void send(OutboxMessage message) {
				// Bilerek boş: demo kanal gönderim yapmaz.
			}
		};
	}

}
