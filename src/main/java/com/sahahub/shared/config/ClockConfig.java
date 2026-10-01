package com.sahahub.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "Şimdi" bilgisini doğrudan Instant.now() yerine bu Clock üzerinden alırız.
 * Böylece testlerde zamanı sabitleyip iptal sınırı gibi kuralları kesin anlarda deneyebiliriz.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
