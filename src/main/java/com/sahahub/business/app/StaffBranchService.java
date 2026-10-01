package com.sahahub.business.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.domain.RolePermissions;
import com.sahahub.identity.domain.StaffMembership;
import com.sahahub.identity.domain.StaffMembershipRepository;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;

/** Personelin erişebildiği şubelerin listesi (panel menüsü ve şube seçici için). */
@Service
@Transactional(readOnly = true)
public class StaffBranchService {

	/** Personelin erişebildiği şube. Menüde yalnızca yetkili bağlantılar gösterilsin diye izin bayrakları da var. */
	public record BranchRef(Long id, String name, Long businessId, String businessName, StaffRole role) {

		public boolean seriesAccess() {
			return RolePermissions.allows(role, Permission.RESERVATION_CREATE);
		}

		public boolean cashAccess() {
			return RolePermissions.allows(role, Permission.CASH_MANAGE);
		}

		public boolean pricingAccess() {
			return RolePermissions.allows(role, Permission.PRICE_MANAGE);
		}

	}

	private final StaffMembershipRepository memberships;
	private final BranchRepository branches;
	private final BusinessRepository businesses;

	public StaffBranchService(StaffMembershipRepository memberships, BranchRepository branches,
			BusinessRepository businesses) {
		this.memberships = memberships;
		this.branches = branches;
		this.businesses = businesses;
	}

	public List<BranchRef> branchesFor(AppUserPrincipal user) {
		Map<Long, BranchRef> result = new LinkedHashMap<>();
		List<StaffMembership> mine = new ArrayList<>(memberships.findByUserIdAndActiveTrue(user.id()));
		mine.sort(Comparator.comparing(StaffMembership::getRole)); // önce en yetkili görev
		for (StaffMembership m : mine) {
			String businessName = businesses.findById(m.getBusinessId()).map(b -> b.getName()).orElse("");
			List<Branch> covered = m.getRole() == StaffRole.OWNER
					? branches.findByBusinessIdAndArchivedFalseOrderByName(m.getBusinessId())
					: branches.findByIdInAndArchivedFalseOrderByName(List.of(m.getBranchId()));
			for (Branch b : covered) {
				result.putIfAbsent(b.getId(),
						new BranchRef(b.getId(), b.getName(), b.getBusinessId(), businessName, m.getRole()));
			}
		}
		return List.copyOf(result.values());
	}

}
