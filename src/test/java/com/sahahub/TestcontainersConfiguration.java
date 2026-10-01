package com.sahahub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testler gerçek PostgreSQL üzerinde çalışır (H2 değil): EXCLUDE kısıtı, btree_gist ve
 * SKIP LOCKED gibi özellikler yalnızca gerçek veritabanında doğru denenebilir.
 * E-posta gönderimi de gerçek bir SMTP sunucusuna (Mailpit) yapılır; dışarıya mesaj çıkmaz.
 * Sürümler compose.yaml ile aynıdır.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String POSTGRES_IMAGE = "postgres:18.6-alpine3.24";
	public static final String MAILPIT_IMAGE = "axllent/mailpit:v1.31.3";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));
	}

	@Bean
	GenericContainer<?> mailpitContainer() {
		return new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE)).withExposedPorts(1025, 8025)
			.waitingFor(Wait.forHttp("/livez").forPort(8025));
	}

	@Bean
	DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
		return registry -> {
			registry.add("spring.mail.host", mailpitContainer::getHost);
			registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(1025));
			registry.add("test.mailpit.api",
					() -> "http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(8025));
		};
	}

}
