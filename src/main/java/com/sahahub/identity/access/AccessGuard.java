package com.sahahub.identity.access;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.domain.RolePermissions;
import com.sahahub.identity.domain.StaffMembership;
import com.sahahub.identity.domain.StaffMembershipRepository;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;

/**
 * Sunucu taraflı yetki kontrolünün tek giriş noktası.
 * <p>
 * Kontrol iki parçalıdır ve ikisi de sağlanmalıdır:
 * <ol>
 * <li>Kapsam: kullanıcının bu işletmede, bu şubeyi kapsayan aktif bir görevi var mı?</li>
 * <li>İzin: o görevin rolü bu işleme izin veriyor mu? (RolePermissions)</li>
 * </ol>
 * Çağıran taraf şubenin işletme kimliğini KAYITTAN okuyup verir (istekten gelen değeri değil);
 * böylece URL'de başka işletmenin şube numarası yazılsa bile kapsam kontrolü başarısız olur.
 */
@Service
public class AccessGuard {

	private final StaffMembershipRepository memberships;

	public AccessGuard(StaffMembershipRepository memberships) {
		this.memberships = memberships;
	}

	@Transactional(readOnly = true)
	public StaffRole requireBranch(AppUserPrincipal user, Long businessId, Long branchId, Permission permission) {
		return effectiveRole(user, businessId, branchId)
			.filter(role -> RolePermissions.allows(role, permission))
			.orElseThrow(() -> new AccessDeniedException("Bu işlem için yetkiniz yok."));
	}

	@Transactional(readOnly = true)
	public boolean can(AppUserPrincipal user, Long businessId, Long branchId, Permission permission) {
		return effectiveRole(user, businessId, branchId).filter(role -> RolePermissions.allows(role, permission))
			.isPresent();
	}

	/** Kullanıcının bu şubedeki en yetkili rolü (OWNER > BRANCH_MANAGER > RECEPTION). */
	@Transactional(readOnly = true)
	public Optional<StaffRole> effectiveRole(AppUserPrincipal user, Long businessId, Long branchId) {
		if (user == null) {
			return Optional.empty();
		}
		List<StaffMembership> mine = memberships.findByUserIdAndBusinessIdAndActiveTrue(user.id(), businessId);
		return mine.stream()
			.filter(m -> m.covers(businessId, branchId))
			.map(StaffMembership::getRole)
			.min(Comparator.naturalOrder()); // enum sırası: OWNER, BRANCH_MANAGER, RECEPTION
	}

}
