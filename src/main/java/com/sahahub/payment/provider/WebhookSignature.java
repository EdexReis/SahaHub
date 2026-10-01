package com.sahahub.payment.provider;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook gövdesinin HMAC-SHA256 imzası. Sağlayıcı imzalar, uygulama doğrular; imzası tutmayan
 * istek (sahte "ödeme başarılı" bildirimi) reddedilir.
 */
public final class WebhookSignature {

	private WebhookSignature() {
	}

	public static String sign(String secret, String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Sabit zamanlı karşılaştırma: imzanın ne kadarının doğru olduğu süre ölçülerek tahmin edilemez. */
	public static boolean verify(String secret, String body, String signature) {
		if (signature == null) {
			return false;
		}
		byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
	}

}
