package com.sahahub.notification.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

	/**
	 * Zamanı gelmiş bekleyen mesajlar. SKIP LOCKED: aynı anda çalışan iki gönderici aynı mesajı almaz;
	 * böylece görev tekrar çalıştırılsa veya iki kopya çalışsa da mesaj bir kez gönderilir.
	 */
	@Query(value = """
			select id from notification_outbox where status = 'PENDING' and next_attempt_at <= :now
			order by id limit :limit for update skip locked""", nativeQuery = true)
	List<Long> lockDue(Instant now, int limit);

	List<OutboxMessage> findByChannelInOrderByIdDesc(Collection<OutboxMessage.Channel> channels, Pageable page);

	List<OutboxMessage> findByDedupKeyStartingWith(String prefix);

}
