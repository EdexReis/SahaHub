package com.sahahub.platform.app;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.domain.Business;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Platform yöneticisi işlemleri. Her müdahale gerekçesiyle denetim kaydına yazılır.
 * Yol düzeyindeki ROLE_PLATFORM_ADMIN kontrolüne ek olarak burada da rol doğrulanır.
 */
@Service
public class PlatformAdminService {

	public record BusinessRow(Long id, String name, Business.Status status, String email) {
	}

	private final BusinessRepository businesses;
	private final AuditService audit;

	public PlatformAdminService(BusinessRepository businesses, AuditService audit) {
		this.businesses = businesses;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<BusinessRow> businesses(AppUserPrincipal admin) {
		requireAdmin(admin);
		return businesses.findAll()
			.stream()
			.map(b -> new BusinessRow(b.getId(), b.getName(), b.getStatus(), b.getEmail()))
			.toList();
	}

	@Transactional
	public String changeStatus(AppUserPrincipal admin, Long businessId, boolean suspend, String reason) {
		requireAdmin(admin);
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("Müdahale gerekçesini yazın.");
		}
		Business b = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("İşletme"));
		if (suspend) {
			b.suspend();
		}
		else {
			b.activate();
		}
		audit.record(admin.id(), b.getId(), "PLATFORM_BUSINESS_STATUS_CHANGED", "Business", b.getId(),
				"status=" + b.getStatus() + ", reason=" + reason.strip());
		return b.getName();
	}

	private static void requireAdmin(AppUserPrincipal user) {
		if (user == null || !user.platformAdmin()) {
			throw new org.springframework.security.access.AccessDeniedException("Platform yöneticisi değilsiniz.");
		}
	}

}
