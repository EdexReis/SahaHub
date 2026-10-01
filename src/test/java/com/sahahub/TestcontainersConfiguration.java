package com.sahahub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testler gerçek PostgreSQL üzerinde çalışır (H2 değil): EXCLUDE kısıtı, btree_gist ve
 * SKIP LOCKED gibi özellikler yalnızca gerçek veritabanında doğru denenebilir.
 * Sürüm compose.yaml ile aynıdır.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String POSTGRES_IMAGE = "postgres:18.6-alpine3.24";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
	}

}
