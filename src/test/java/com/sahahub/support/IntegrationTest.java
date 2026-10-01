package com.sahahub.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.sahahub.TestcontainersConfiguration;

/**
 * Gerçek PostgreSQL (Testcontainers) ile tam uygulama bağlamı. Tüm entegrasyon testleri
 * aynı bağlamı paylaşır (Spring test bağlam önbelleği), konteyner bir kez başlar.
 * Her test kendi işletme/şube/sahasını oluşturur; testler birbirinin verisine dokunmaz.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, IntegrationTest.ClockConfig.class, TestData.class })
public @interface IntegrationTest {

	/** Testler sabit bir "şimdi" ile başlar: 2 Mart 2026 Pazartesi 09:00 İstanbul (06:00 UTC). */
	Instant START = Instant.parse("2026-03-02T06:00:00Z");

	@TestConfiguration(proxyBeanMethods = false)
	class ClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(START);
		}

	}

}
