package com.sahahub.shared.audit;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.shared.web.RequestIdFilter;

/**
 * Denetim kaydı yazar. Çağıran işlemle AYNI transaction içinde çalışır (MANDATORY):
 * değişiklik geri alınırsa denetim kaydı da geri alınır; ikisi her zaman tutarlıdır.
 */
@Service
public class AuditService {

	private final AuditEventRepository repository;
	private final Clock clock;

	public AuditService(AuditEventRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long actorId, Long businessId, String action, String entityType, Long entityId,
			String details) {
		String safeDetails = details != null && details.length() > 2000 ? details.substring(0, 2000) : details;
		repository.save(new AuditEvent(Instant.now(clock), actorId, businessId, action, entityType, entityId,
				safeDetails, MDC.get(RequestIdFilter.MDC_KEY)));
	}

}
