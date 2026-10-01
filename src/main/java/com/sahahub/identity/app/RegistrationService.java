package com.sahahub.identity.app;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.shared.domain.BusinessRuleException;

@Service
public class RegistrationService {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public RegistrationService(AppUserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/** Müşteri hesabı açar. Parola düz metin olarak hiçbir yerde saklanmaz veya loglanmaz. */
	@Transactional
	public AppUser registerCustomer(String email, String rawPassword, String fullName, String phone) {
		if (users.existsByEmail(email.strip())) {
			throw new BusinessRuleException("Bu e-posta adresiyle zaten bir hesap var.");
		}
		try {
			return users.saveAndFlush(
					new AppUser(email, passwordEncoder.encode(rawPassword), fullName, phone, Instant.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			// Aynı e-postayla eşzamanlı iki kayıt: tekil indeks ikincisini reddeder
			throw new BusinessRuleException("Bu e-posta adresiyle zaten bir hesap var.");
		}
	}

}
