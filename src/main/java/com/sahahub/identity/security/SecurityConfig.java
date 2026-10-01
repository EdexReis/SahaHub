package com.sahahub.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Oturum tabanlı kimlik doğrulama + CSRF (Spring Security'de varsayılan açık).
 * <p>
 * Buradaki URL kuralları kaba bir ilk süzgeçtir. Asıl yetki kontrolü — kullanıcının
 * o şubeye/işletmeye/rezervasyona erişimi olup olmadığı — servis katmanında AccessGuard
 * ile yapılır. Menüde bir bağlantıyı gizlemek güvenlik sayılmaz.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	public static final String LOGIN_PATH = "/giris";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptService attempts,
			org.springframework.security.core.session.SessionRegistry sessionRegistry) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/sahalar", "/sahalar/**", "/api/sahalar/**", "/kayit", LOGIN_PATH, "/error",
						"/parolami-unuttum", "/parola-sifirla/*",
						"/webhooks/**",
						"/css/**", "/js/**", "/vendor/**", "/fonts/**", "/img/**", "/favicon.svg",
						"/actuator/health")
				.permitAll()
				// Herkese açık okuma: ilan listesi/ayrıntısı, ligler, davet önizlemesi (katılmak için giriş gerekir)
				.requestMatchers(org.springframework.http.HttpMethod.GET, "/ilanlar", "/ilanlar/{id:[0-9]+}", "/ligler",
						"/ligler/{id:[0-9]+}", "/davet/*", "/saha-fotograf/*",
						"/takim-logo/*")
				.permitAll()
				.requestMatchers("/admin/**").hasRole("PLATFORM_ADMIN")
				.requestMatchers("/isletme/**").hasRole("STAFF")
				.anyRequest().authenticated())
			// Sağlayıcı bildirimleri tarayıcıdan gelmez; CSRF yerine HMAC imzasıyla doğrulanır
			.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**"))
			.formLogin(form -> form
				.loginPage(LOGIN_PATH)
				.loginProcessingUrl(LOGIN_PATH)
				.usernameParameter("email")
				.passwordParameter("password")
				.successHandler(new LoginHandlers.Success(attempts))
				.failureHandler(new LoginHandlers.Failure(attempts))
				.permitAll())
			.logout(logout -> logout
				.logoutUrl("/cikis")
				.logoutSuccessUrl("/?cikis")
				.deleteCookies("JSESSIONID"))
			.sessionManagement(session -> session
				// Girişte oturum kimliği yenilenir (session fixation koruması)
				.sessionFixation(fixation -> fixation.changeSessionId())
				// Oturumlar kayıt altında tutulur; parola sıfırlanınca kullanıcının tüm oturumları sonlandırılır
				.maximumSessions(-1)
				.sessionRegistry(sessionRegistry)
				.expiredUrl(LOGIN_PATH + "?oturum"))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(
						"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
								+ "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
								+ "frame-ancestors 'none'; form-action 'self'; base-uri 'self'"))
				.referrerPolicy(ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				// Uygulama kamera, mikrofon, konum vb. kullanmaz; gömülü içerik de kullanamasın
				.permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
			.addFilterBefore(new LoginRateLimitFilter(attempts), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	org.springframework.security.core.session.SessionRegistry sessionRegistry() {
		return new org.springframework.security.core.session.SessionRegistryImpl();
	}

	/** Oturum sona erdiğinde SessionRegistry'den düşülmesi için. */
	@Bean
	org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
		return new org.springframework.security.web.session.HttpSessionEventPublisher();
	}

	/**
	 * Parolalar "{bcrypt}..." biçiminde saklanır. DelegatingPasswordEncoder, ileride
	 * algoritma değişirse eski özetlerin de doğrulanabilmesini sağlar.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
