package com.sahahub.payment.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.sahahub.payment.provider.WebhookEndpoint;

/**
 * Sağlayıcının sunucudan sunucuya bildirim ucu. Oturum ve CSRF yerine HMAC imzasıyla korunur
 * (SecurityConfig'te /webhooks/** CSRF dışıdır). Yinelenen bildirim de 200 döner; böylece
 * sağlayıcı aynı olayı tekrar tekrar göndermeye devam etmez.
 */
@RestController
public class PaymentWebhookController {

	private final WebhookEndpoint endpoint;

	public PaymentWebhookController(WebhookEndpoint endpoint) {
		this.endpoint = endpoint;
	}

	@PostMapping("/webhooks/odeme/simulasyon")
	public ResponseEntity<String> receive(@RequestBody String body,
			@RequestHeader(name = "X-Sim-Signature", required = false) String signature) {
		try {
			return ResponseEntity.ok(endpoint.receive(body, signature).name());
		}
		catch (SecurityException ex) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
		}
	}

}
