package com.sahahub.identity.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Giriş denemesi hız sınırı: aynı e-posta + IP için art arda N başarısız denemeden sonra
 * belirli bir süre giriş engellenir.
 * <p>
 * Bilinen sınır: sayaçlar bellekte tutulur. Tek sunucuda çalışan bu sürüm için yeterlidir;
 * birden fazla sunucuya geçilirse paylaşılan bir depoya (ör. veritabanı) taşınmalıdır.
 */
@Service
public class LoginAttemptService {

	private record Attempts(int failures, Instant lockedUntil) {
	}

	private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
	private final SecurityProperties properties;
	private final Clock clock;

	public LoginAttemptService(SecurityProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public boolean isLocked(String email, String ip) {
		Attempts a = attempts.get(key(email, ip));
		return a != null && a.lockedUntil() != null && Instant.now(clock).isBefore(a.lockedUntil());
	}

	public void recordFailure(String email, String ip) {
		Instant now = Instant.now(clock);
		attempts.compute(key(email, ip), (k, old) -> {
			int failures = old == null || (old.lockedUntil() != null && !now.isBefore(old.lockedUntil()))
					? 1
					: old.failures() + 1;
			Instant lockedUntil = failures >= properties.loginMaxFailures()
					? now.plus(properties.loginLockDuration())
					: null;
			return new Attempts(failures, lockedUntil);
		});
	}

	public void recordSuccess(String email, String ip) {
		attempts.remove(key(email, ip));
	}

	private static String key(String email, String ip) {
		return (email == null ? "" : email.strip().toLowerCase(Locale.ROOT)) + "|" + ip;
	}

}
