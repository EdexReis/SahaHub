package com.sahahub.notification.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.sahahub.notification.domain.OutboxMessage;

/**
 * SMTP ile e-posta. Yerelde Mailpit'e gider (localhost:1025, arayüz http://localhost:8025); gerçek bir
 * kullanıcıya ulaşmaz. Canlı ortamda MAIL_HOST / MAIL_SMTP_PORT ile gerçek SMTP verilmesi ayrı bir
 * karardır (bkz. README "Bilinen eksikler").
 */
@Component
class EmailChannel implements MessageChannel {

	private final JavaMailSender mail;
	private final String from;

	EmailChannel(JavaMailSender mail, @Value("${sahahub.notification.mail-from:SahaHub <bildirim@sahahub.local>}") String from) {
		this.mail = mail;
		this.from = from;
	}

	@Override
	public OutboxMessage.Channel channel() {
		return OutboxMessage.Channel.EMAIL;
	}

	@Override
	public String provider() {
		return "SMTP";
	}

	@Override
	public void send(OutboxMessage m) {
		SimpleMailMessage msg = new SimpleMailMessage();
		msg.setFrom(from);
		msg.setTo(m.getRecipient());
		msg.setSubject("SahaHub: " + m.getSubject());
		msg.setText(m.getBody());
		mail.send(msg);
	}

}
