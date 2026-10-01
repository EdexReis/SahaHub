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
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptService attempts) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/sahalar", "/sahalar/**", "/api/sahalar/**", "/kayit", LOGIN_PATH, "/error",
						"/css/**", "/js/**", "/vendor/**", "/fonts/**", "/img/**", "/favicon.svg",
						"/actuator/health")
				.permitAll()
				.requestMatchers("/admin/**").hasRole("PLATFORM_ADMIN")
				.requestMatchers("/isletme/**").hasRole("STAFF")
				.anyRequest().authenticated())
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
				.sessionFixation(fixation -> fixation.changeSessionId()))
			.headers(headers -> headers
				.contentSecurityPolicy(csp -> csp.policyDirectives(
						"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
								+ "img-src 'self' data:; font-src 'self'; connect-src 'self'; "
								+ "frame-ancestors 'none'; form-action 'self'; base-uri 'self'"))
				.referrerPolicy(ref -> ref.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
			.addFilterBefore(new LoginRateLimitFilter(attempts), UsernamePasswordAuthenticationFilter.class);
		return http.build();
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
