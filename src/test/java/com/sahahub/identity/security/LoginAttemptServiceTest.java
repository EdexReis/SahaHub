package com.sahahub.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.sahahub.support.MutableClock;

class LoginAttemptServiceTest {

	final MutableClock clock = new MutableClock(Instant.parse("2026-03-02T06:00:00Z"));
	final LoginAttemptService service = new LoginAttemptService(new SecurityProperties(5, Duration.ofMinutes(15)),
			clock);

	@Test
	void locksAfterFiveFailuresForFifteenMinutes() {
		for (int i = 0; i < 4; i++) {
			service.recordFailure("Ali@Test.local", "10.0.0.1");
		}
		assertThat(service.isLocked("ali@test.local", "10.0.0.1")).isFalse();
		service.recordFailure("ali@test.local", "10.0.0.1");
		assertThat(service.isLocked("ALI@test.local", "10.0.0.1")).isTrue(); // büyük/küçük harf duyarsız
		assertThat(service.isLocked("ali@test.local", "10.0.0.2")).isFalse(); // başka IP etkilenmez

		clock.advance(Duration.ofMinutes(15));
		assertThat(service.isLocked("ali@test.local", "10.0.0.1")).isFalse();
	}

	@Test
	void successResetsCounter() {
		for (int i = 0; i < 4; i++) {
			service.recordFailure("ali@test.local", "10.0.0.1");
		}
		service.recordSuccess("ali@test.local", "10.0.0.1");
		service.recordFailure("ali@test.local", "10.0.0.1");
		assertThat(service.isLocked("ali@test.local", "10.0.0.1")).isFalse();
	}

	@Test
	void turkishCapitalIDoesNotBreakKeyNormalization() {
		// Türkçe varsayılan dilde "I".toLowerCase() → "ı" olurdu; anahtar Locale.ROOT ile küçültülür
		for (int i = 0; i < 5; i++) {
			service.recordFailure("ILKER@test.local", "10.0.0.1");
		}
		assertThat(service.isLocked("ilker@test.local", "10.0.0.1")).isTrue();
	}

}
