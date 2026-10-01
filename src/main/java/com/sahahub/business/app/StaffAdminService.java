package com.sahahub.business.app;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.Business;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.domain.StaffMembership;
import com.sahahub.identity.domain.StaffMembershipRepository;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Personel ve yetkiler (STAFF_MANAGE) ile denetim kayıtları (AUDIT_VIEW). İkisi de işletme genelidir;
 * yalnızca işletme sahibi kullanır.
 * <ul>
 * <li>Görevlendirme mevcut bir SahaHub hesabına yapılır (e-posta ile). Davet e-postası gönderilmez.</li>
 * <li>Görev silinmez, pasifleştirilir; aynı kişi aynı kapsama yeniden atanırsa eski satır canlanır.</li>
 * <li>Sahip kendi görevini kaldıramaz; işletmenin son aktif sahibi kaldırılamaz.</li>
 * <li>Yeni yetkiler kişinin bir sonraki girişinde menüye yansır; sunucu kontrolleri ise hemen geçerlidir
 * (her istekte veritabanından okunur).</li>
 * </ul>
 */
@Service
public class StaffAdminService {

	public record StaffRow(Long membershipId, Long userId, String name, String email, StaffRole role,
			String branchName, boolean active, Instant since) {
	}

	public record AuditRow(Instant at, String actor, String action, String entityType, Long entityId, String details,
			String requestId) {
	}

	public record AuditPage(List<AuditRow> rows, int page, boolean hasNext, List<String> actions) {
	}

	public static final int AUDIT_PAGE_SIZE = 50;

	private final StaffMembershipRepository memberships;
	private final AppUserRepository users;
	private final BusinessRepository businesses;
	private final BranchRepository branches;
	private final AccessGuard guard;
	private final AuditService audit;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public StaffAdminService(StaffMembershipRepository memberships, AppUserRepository users,
			BusinessRepository businesses, BranchRepository branches, AccessGuard guard, AuditService audit,
			JdbcTemplate jdbc, Clock clock) {
		this.memberships = memberships;
		this.users = users;
		this.businesses = businesses;
		this.branches = branches;
		this.guard = guard;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<StaffRow> staff(AppUserPrincipal user, Long businessId) {
		guard.requireBusiness(user, businessId, Permission.STAFF_MANAGE);
		return jdbc.query("""
				select m.id, u.id, u.full_name, u.email, m.role, b.name, m.active, m.created_at
				from staff_membership m join app_user u on u.id = m.user_id left join branch b on b.id = m.branch_id
				where m.business_id = ? order by m.active desc, m.role, b.name nulls first, u.full_name""",
				(rs, i) -> new StaffRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
						StaffRole.valueOf(rs.getString(5)), rs.getString(6), rs.getBoolean(7),
						rs.getTimestamp(8).toInstant()),
				businessId);
	}

	@Transactional
	public void assign(AppUserPrincipal user, Long businessId, String email, StaffRole role, Long branchId) {
		guard.requireBusiness(user, businessId, Permission.STAFF_MANAGE);
		Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("İşletme"));
		if (role == null) {
			throw new BusinessRuleException("Rol seçin.");
		}
		if (email == null || email.isBlank()) {
			throw new BusinessRuleException("Kişinin SahaHub hesabındaki e-postasını yazın.");
		}
		AppUser target = users.findByEmail(AppUser.normalizeEmail(email))
			.orElseThrow(() -> new BusinessRuleException(
					"Bu e-postayla kayıtlı hesap yok. Kişi önce SahaHub'a kayıt olmalı."));
		Long scope = null;
		if (role != StaffRole.OWNER) {
			Branch b = branches.findById(branchId == null ? -1L : branchId)
				.filter(x -> x.getBusinessId().equals(businessId) && !x.isArchived())
				.orElseThrow(() -> new BusinessRuleException("Şube yöneticisi ve resepsiyon için şube seçin."));
			scope = b.getId();
		}
		Optional<StaffMembership> existing = findScope(target.getId(), businessId, scope);
		if (existing.isPresent()) {
			StaffMembership m = existing.get();
			if (m.isActive() && m.getRole() == role) {
				throw new BusinessRuleException("Bu kişi bu görevde zaten aktif.");
			}
			m.reactivate(role);
		}
		else {
			Instant now = Instant.now(clock);
			memberships.save(role == StaffRole.OWNER ? StaffMembership.owner(target.getId(), businessId, now)
					: StaffMembership.forBranch(target.getId(), businessId, scope, role, now));
		}
		audit.record(user.id(), business.getId(), "STAFF_ASSIGNED", "AppUser", target.getId(),
				role + (scope == null ? "" : " branch=" + scope));
	}

	@Transactional
	public void revoke(AppUserPrincipal user, Long businessId, Long membershipId) {
		guard.requireBusiness(user, businessId, Permission.STAFF_MANAGE);
		StaffMembership m = memberships.findById(membershipId)
			.filter(x -> x.getBusinessId().equals(businessId))
			.orElseThrow(() -> new NotFoundException("Görev"));
		if (!m.isActive()) {
			return;
		}
		if (m.getUserId().equals(user.id())) {
			throw new BusinessRuleException("Kendi görevinizi kaldıramazsınız.");
		}
		if (m.getRole() == StaffRole.OWNER) {
			Integer owners = jdbc.queryForObject(
					"select count(*) from staff_membership where business_id = ? and role = 'OWNER' and active",
					Integer.class, businessId);
			if (owners != null && owners <= 1) {
				throw new BusinessRuleException("İşletmenin son sahibi kaldırılamaz.");
			}
		}
		m.deactivate();
		audit.record(user.id(), businessId, "STAFF_REVOKED", "AppUser", m.getUserId(),
				m.getRole() + (m.getBranchId() == null ? "" : " branch=" + m.getBranchId()));
	}

	/** İşletmenin denetim kayıtları, en yeni önce, sayfalı; isteğe bağlı işlem ve tarih süzgeci. */
	@Transactional(readOnly = true)
	public AuditPage audit(AppUserPrincipal user, Long businessId, String action, LocalDate from, LocalDate to,
			int page) {
		guard.requireBusiness(user, businessId, Permission.AUDIT_VIEW);
		ZoneId zone = ZoneId.of("Europe/Istanbul");
		Timestamp f = from == null ? null : Timestamp.from(from.atStartOfDay(zone).toInstant());
		Timestamp t = to == null ? null : Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
		String a = action == null || action.isBlank() ? null : action;
		int p = Math.max(0, page);
		List<AuditRow> rows = jdbc.query("""
				select e.occurred_at, u.full_name, e.action, e.entity_type, e.entity_id, e.details, e.request_id
				from audit_event e left join app_user u on u.id = e.actor_id
				where e.business_id = ? and (cast(? as varchar) is null or e.action = ?)
				  and (cast(? as timestamptz) is null or e.occurred_at >= ?)
				  and (cast(? as timestamptz) is null or e.occurred_at < ?)
				order by e.occurred_at desc, e.id desc limit ? offset ?""",
				(rs, i) -> new AuditRow(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
						rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getString(7)),
				businessId, a, a, f, f, t, t, AUDIT_PAGE_SIZE + 1, p * AUDIT_PAGE_SIZE);
		boolean hasNext = rows.size() > AUDIT_PAGE_SIZE;
		List<String> actions = jdbc.queryForList(
				"select distinct action from audit_event where business_id = ? order by action", String.class,
				businessId);
		return new AuditPage(hasNext ? rows.subList(0, AUDIT_PAGE_SIZE) : rows, p, hasNext, actions);
	}

	private Optional<StaffMembership> findScope(Long userId, Long businessId, Long branchId) {
		List<Long> ids = jdbc.queryForList("""
				select id from staff_membership where user_id = ? and business_id = ?
				and coalesce(branch_id, 0) = coalesce(cast(? as bigint), 0)""", Long.class, userId, businessId,
				branchId);
		return ids.isEmpty() ? Optional.empty() : memberships.findById(ids.getFirst());
	}

}
