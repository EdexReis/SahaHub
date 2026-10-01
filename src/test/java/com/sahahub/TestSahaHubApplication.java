package com.sahahub;

import org.springframework.boot.SpringApplication;

/**
 * Yerelde PostgreSQL kurmadan uygulamayı Testcontainers veritabanıyla başlatmak için:
 * Eclipse'te bu sınıfa sağ tık → Run As → Java Application.
 */
public class TestSahaHubApplication {

	public static void main(String[] args) {
		SpringApplication.from(SahaHubApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
