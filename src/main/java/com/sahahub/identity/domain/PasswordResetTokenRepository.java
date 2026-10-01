package com.sahahub.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	/** Kullanım sırasında kilitlenir: aynı bağlantı iki sekmeden aynı anda iki kez kullanılamaz. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from PasswordResetToken t where t.tokenHash = :hash")
	Optional<PasswordResetToken> findForUpdate(String hash);

	@Query("select t from PasswordResetToken t where t.userId = :userId and t.usedAt is null")
	List<PasswordResetToken> openForUser(Long userId);

	@Query("select count(t) from PasswordResetToken t where t.userId = :userId and t.createdAt > :since")
	long createdSince(Long userId, Instant since);

}
