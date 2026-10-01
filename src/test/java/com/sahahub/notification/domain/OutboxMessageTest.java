package com.sahahub.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxMessageTest {

	@Test
	void failedAttemptsBackOffThenGiveUp() {
		OutboxMessage m = new OutboxMessage();
		ReflectionTestUtils.setField(m, "status", OutboxMessage.Status.PENDING);
		Instant now = Instant.parse("2026-03-02T06:00:00Z");

		long[] waits = { 1, 2, 4, 8 };
		for (long minutes : waits) {
			m.markFailedAttempt("SMTP bağlantısı yok", now);
			assertThat(m.getStatus()).isEqualTo(OutboxMessage.Status.PENDING);
			assertThat(ReflectionTestUtils.getField(m, "nextAttemptAt")).isEqualTo(now.plus(Duration.ofMinutes(minutes)));
		}
		m.markFailedAttempt("x".repeat(500), now);
		assertThat(m.getStatus()).isEqualTo(OutboxMessage.Status.FAILED);
		assertThat(m.getAttempts()).isEqualTo(OutboxMessage.MAX_ATTEMPTS);
		assertThat(m.getLastError()).hasSize(300);
	}

	@Test
	void sentRecordsProviderAndTime() {
		OutboxMessage m = new OutboxMessage();
		Instant now = Instant.parse("2026-03-02T06:00:00Z");
		m.markSent("DEMO", now);
		assertThat(m.getStatus()).isEqualTo(OutboxMessage.Status.SENT);
		assertThat(m.getProvider()).isEqualTo("DEMO");
		assertThat(m.getSentAt()).isEqualTo(now);
		assertThat(m.getAttempts()).isEqualTo(1);
	}

}
