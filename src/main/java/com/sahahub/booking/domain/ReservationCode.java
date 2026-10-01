package com.sahahub.booking.domain;

import java.security.SecureRandom;

/**
 * Müşteriye gösterilen ve URL'lerde kullanılan rezervasyon kodu (ör. "K7P3QX9M").
 * Ardışık id yerine rastgele kod kullanılır; böylece kodlar tahmin edilerek
 * başkasının rezervasyonu aranamaz. Karışan karakterler (0/O, 1/I/L) yoktur.
 */
public final class ReservationCode {

	private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private ReservationCode() {
	}

	public static String generate() {
		char[] code = new char[8];
		for (int i = 0; i < code.length; i++) {
			code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
		}
		return new String(code);
	}

}
