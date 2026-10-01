package com.sahahub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.sahahub.identity.app.PasswordResetService;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.support.IntegrationTest;
import com.sahahub.support.MutableClock;
import com.sahahub.support.TestData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parola sıfırlama: hesap sızdırmaz, token özetlenir, süreli ve tek kullanımlık; e-posta gerçek SMTP'den. */
@IntegrationTest
@AutoConfigureMockMvc
class PasswordResetIT {

	static final Pattern LINK = Pattern.compile("/parola-sifirla/([A-Za-z0-9_\\-]{40,})");

	@Autowired
	PasswordResetService service;

	@Autowired
	AppUserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	TestData data;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mvc;

	@Value("${test.mailpit.api}")
	String mailpit;

	final JsonMapper json = JsonMapper.builder().build();

	@BeforeEach
	void resetClock() {
		clock.set(IntegrationTest.START);
	}

	/** Bu adrese gelen sıfırlama e-postalarındaki token'lar (en yeni önce). */
	List<String> tokensSentTo(String email) throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String q = URLEncoder.encode("to:" + email, StandardCharsets.UTF_8);
		JsonNode list = json.readTree(http.send(HttpRequest.newBuilder(URI.create(mailpit + "/api/v1/search?query=" + q))
			.build(), HttpResponse.BodyHandlers.ofString()).body());
		List<String> out = new ArrayList<>();
		for (JsonNode m : list.get("messages")) {
			String body = json.readTree(http.send(
					HttpRequest.newBuilder(URI.create(mailpit + "/api/v1/message/" + m.get("ID").asString())).build(),
					HttpResponse.BodyHandlers.ofString()).body()).get("Text").asString();
			Matcher x = LINK.matcher(body);
			if (x.find()) {
				out.add(x.group(1));
			}
		}
		return out;
	}

	@Test
	void fullFlowViaRealEmail_tokenStoredOnlyAsHash_singleUse() throws Exception {
		AppUserPrincipal u = data.customer();
		mvc.perform(post("/parolami-unuttum").param("email", u.email().toUpperCase(java.util.Locale.ROOT))
			.with(r -> {
				r.setRemoteAddr("10.0.0.1");
				return r;
			}).with(csrf()))
			.andExpect(flash().attribute("flashSuccess", Matchers.startsWith("Bu e-posta kayıtlıysa")));
		List<String> sent = tokensSentTo(u.email());
		assertThat(sent).hasSize(1);
		String token = sent.getFirst();

		// Veritabanında token'ın kendisi yok, yalnızca özeti
		assertThat(jdbc.queryForObject("select count(*) from password_reset_token where token_hash = ?", Integer.class,
				token)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from password_reset_token where user_id = ?", Integer.class,
				u.id())).isEqualTo(1);

		mvc.perform(get("/parola-sifirla/{t}", token)).andExpect(status().isOk())
			.andExpect(content().string(Matchers.containsString("Parolayı değiştir")));
		mvc.perform(post("/parola-sifirla/{t}", token).param("password", "yeni-parola-123").param("repeat", "farkli-123456")
			.with(csrf())).andExpect(content().string(Matchers.containsString("Parolalar aynı değil.")));
		mvc.perform(post("/parola-sifirla/{t}", token).param("password", "yeni-parola-123").param("repeat", "yeni-parola-123")
			.with(csrf())).andExpect(flash().attribute("flashSuccess", "Parolanız değişti. Yeni parolanızla giriş yapın."));
		assertThat(encoder.matches("yeni-parola-123", users.findById(u.id()).orElseThrow().getPasswordHash())).isTrue();

		// Tek kullanımlık
		assertThatThrownBy(() -> service.reset(token, "baska-parola-1", "baska-parola-1"))
			.isInstanceOf(BusinessRuleException.class);
		mvc.perform(get("/parola-sifirla/{t}", token))
			.andExpect(content().string(Matchers.containsString("Yeni bağlantı iste")));
		assertThat(jdbc.queryForObject("select count(*) from audit_event where action = 'PASSWORD_RESET' and actor_id = ?",
				Integer.class, u.id())).isEqualTo(1);
	}

	@Test
	void unknownEmailGetsTheSameAnswerAndNoMail() throws Exception {
		mvc.perform(post("/parolami-unuttum").param("email", "kimse-yok@test.local").with(r -> {
			r.setRemoteAddr("10.0.0.2");
			return r;
		}).with(csrf())).andExpect(flash().attribute("flashSuccess", Matchers.startsWith("Bu e-posta kayıtlıysa")));
		assertThat(tokensSentTo("kimse-yok@test.local")).isEmpty();
	}

	@Test
	void expiresAfterThirtyMinutes_andNewRequestInvalidatesOld() throws Exception {
		AppUserPrincipal u = data.customer();
		service.request(u.email(), "10.0.0.3");
		String first = tokensSentTo(u.email()).getFirst();
		service.request(u.email(), "10.0.0.3");
		String second = tokensSentTo(u.email()).stream().filter(t -> !t.equals(first)).findFirst().orElseThrow();
		assertThat(service.isUsable(first)).as("yeni istek eskisini geçersiz kılar").isFalse();
		assertThat(service.isUsable(second)).isTrue();

		clock.advance(PasswordResetService.LIFETIME.plusSeconds(1));
		assertThat(service.isUsable(second)).isFalse();
		assertThatThrownBy(() -> service.reset(second, "yeni-parola-123", "yeni-parola-123"))
			.isInstanceOf(BusinessRuleException.class).hasMessageContaining("süresi dolmuş");
	}

	@Test
	void rateLimits() throws Exception {
		AppUserPrincipal u = data.customer();
		for (int i = 0; i < 5; i++) {
			service.request(u.email(), "10.0.0.4");
		}
		assertThat(jdbc.queryForObject("select count(*) from password_reset_token where user_id = ?", Integer.class,
				u.id())).isEqualTo(PasswordResetService.MAX_PER_ACCOUNT_PER_HOUR);
		for (int i = 5; i < PasswordResetService.MAX_PER_IP_PER_HOUR; i++) {
			service.request("biri-" + i + "@test.local", "10.0.0.4");
		}
		assertThatThrownBy(() -> service.request(u.email(), "10.0.0.4")).isInstanceOf(BusinessRuleException.class)
			.hasMessageContaining("Çok fazla");
		clock.advance(Duration.ofMinutes(61));
		service.request(u.email(), "10.0.0.4"); // bir saat sonra yeniden
	}

}
