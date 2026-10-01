package com.sahahub.shared.audit;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

	List<AuditEvent> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);

	List<AuditEvent> findByActionOrderByIdAsc(String action);

}
