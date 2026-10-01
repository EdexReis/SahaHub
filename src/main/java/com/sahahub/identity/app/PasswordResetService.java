package com.sahahub.identity.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.PasswordResetToken;
import com.sahahub.identity.domain.PasswordResetTokenRepository;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;

/**
 * Parola sıfırlama.
 * <ul>
 * <li><b>Hesap varlığı sızdırılmaz</b>: e-posta kayıtlı olsa da olmasa da aynı yanıt verilir.</li>
 * <li>Bağlantı 30 dakika geçerli ve tek kullanımlıktır. Yeni bağlantı istenince eskiler geçersiz olur.</li>
 * <li>Veritabanında yalnızca token'ın SHA-256 özeti tutulur. Token 32 rastgele bayttır (256 bit), bu yüzden
 * tuzsuz hızlı özet yeterlidir; tahmin edilemez.</li>
 * <li>E-posta outbox'a yazılmaz (outbox gövdesi token'ı düz metin saklardı); commit'ten sonra doğrudan
 * gönderilir. Gönderim başarısızsa kullanıcı yeniden ister. Token loglara yazılmaz.</li>
 * <li>Hız sınırı: hesap başına saatte 3 bağlantı (fazlası sessizce gönderilmez), IP başına saatte 10 istek
 * (bellekte; tek sunucu varsayımı).</li>
 * </ul>
 */
@Service
public class PasswordResetService {

	public static final Duration LIFETIME = Duration.ofMinutes(30);
	public static final int MAX_PER_ACCOUNT_PER_HOUR = 3;
	public static final int MAX_PER_IP_PER_HOUR = 10;

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AppUserRepository users;
	private final PasswordResetTokenRepository tokens;
	private final PasswordEncoder encoder;
	private final JavaMailSender mail;
	private final AuditService audit;
	private final Clock clock;
	private final String baseUrl;
	private final String from;
	private final Map<String, Deque<Instant>> perIp = new ConcurrentHashMap<>();
	private final org.springframework.security.core.session.SessionRegistry sessions;

	public PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens, PasswordEncoder encoder,
			JavaMailSender mail, AuditService audit, Clock clock,
			@Value("${sahahub.public-base-url:http://localhost:8080}") String baseUrl,
			@Value("${sahahub.notification.mail-from:SahaHub <bildirim@sahahub.local>}") String from,
			org.springframework.security.core.session.SessionRegistry sessions) {
		this.sessions = sessions;
		this.users = users;
		this.tokens = tokens;
		this.encoder = encoder;
		this.mail = mail;
		this.audit = audit;
		this.clock = clock;
		this.baseUrl = baseUrl;
		this.from = from;
	}

	/**
	 * Bağlantı ister. Hesap yoksa, kapalıysa veya hesap sınırı dolduysa hiçbir şey yapmaz ama aynı şekilde
	 * döner. Yalnızca IP sınırı aşılırsa hata verir (bu, hesap hakkında bilgi vermez).
	 */
	@Transactional
	public void request(String email, String ip) {
		Instant now = Instant.now(clock);
		if (!allowIp(ip, now)) {
			throw new BusinessRuleException("Çok fazla deneme yapıldı. Bir saat sonra yeniden deneyin.");
		}
		if (email == null || email.isBlank() || email.length() > 254) {
			return;
		}
		Optional<AppUser> found = users.findByEmail(AppUser.normalizeEmail(email));
		if (found.isEmpty() || !found.get().isEnabled()) {
			return;
		}
		AppUser u = found.get();
		if (tokens.createdSince(u.getId(), now.minus(Duration.ofHours(1))) >= MAX_PER_ACCOUNT_PER_HOUR) {
			log.info("Parola sıfırlama hesap sınırı doldu user={}", u.getId());
			return;
		}
		tokens.openForUser(u.getId()).forEach(t -> t.consume(now));
		String token = newToken();
		tokens.save(new PasswordResetToken(u.getId(), hash(token), now, now.plus(LIFETIME)));
		String to = u.getEmail();
		String link = baseUrl + "/parola-sifirla/" + token;
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				send(to, link);
			}
		});
	}

	/** Bağlantı hâlâ kullanılabilir mi (formu göstermeden önce). */
	@Transactional(readOnly = true)
	public boolean isUsable(String token) {
		return token != null && tokens.findByTokenHash(hash(token))
			.map(t -> t.isUsableAt(Instant.now(clock)))
			.orElse(false);
	}

	@Transactional
	public void reset(String token, String password, String repeat) {
		Instant now = Instant.now(clock);
		PasswordResetToken t = (token == null ? Optional.<PasswordResetToken>empty() : tokens.findForUpdate(hash(token)))
			.filter(x -> x.isUsableAt(now))
			.orElseThrow(() -> new BusinessRuleException(
					"Bağlantının süresi dolmuş ya da daha önce kullanılmış. Yeni bağlantı isteyin."));
		if (password == null || password.length() < 10 || password.length() > 72) {
			throw new BusinessRuleException("Parola 10 ile 72 karakter arasında olmalı.");
		}
		if (!password.equals(repeat)) {
			throw new BusinessRuleException("Parolalar aynı değil.");
		}
		AppUser u = users.findById(t.getUserId()).orElseThrow();
		u.changePasswordHash(encoder.encode(password));
		t.consume(now);
		tokens.openForUser(u.getId()).forEach(x -> x.consume(now));
		audit.record(u.getId(), null, "PASSWORD_RESET", "AppUser", u.getId(), null);
		expireSessions(u.getId());
	}

	/** Parola değişince açık oturumlar sonlanır (çalınmış bir oturum açık kalmasın). */
	private void expireSessions(Long userId) {
		for (Object p : sessions.getAllPrincipals()) {
			if (p instanceof com.sahahub.identity.security.AppUserPrincipal ap && userId.equals(ap.id())) {
				sessions.getAllSessions(p, false).forEach(org.springframework.security.core.session.SessionInformation::expireNow);
			}
		}
	}

	// ------------------------------------------------------------------ yardımcılar

	private void send(String to, String link) {
		SimpleMailMessage msg = new SimpleMailMessage();
		msg.setFrom(from);
		msg.setTo(to);
		msg.setSubject("SahaHub: parola sıfırlama");
		msg.setText("""
				Parolanızı sıfırlamak için aşağıdaki bağlantıyı 30 dakika içinde açın:

				%s

				Bu isteği siz yapmadıysanız bu e-postayı yok sayın; parolanız değişmez.""".formatted(link));
		try {
			mail.send(msg);
		}
		catch (RuntimeException ex) {
			// Token'ı içeren bağlantı loga yazılmaz
			log.warn("Parola sıfırlama e-postası gönderilemedi: {}", ex.getClass().getSimpleName());
		}
	}

	private synchronized boolean allowIp(String ip, Instant now) {
		String key = ip == null ? "?" : ip;
		Deque<Instant> q = perIp.computeIfAbsent(key, k -> new ArrayDeque<>());
		while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
			q.pollFirst();
		}
		if (q.size() >= MAX_PER_IP_PER_HOUR) {
			return false;
		}
		q.addLast(now);
		return true;
	}

	static String newToken() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	static String hash(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
