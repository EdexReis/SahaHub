package com.sahahub.identity.domain;

import static com.sahahub.identity.domain.Permission.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Rol → izin matrisi. docs/YETKI_MATRISI.md bu tablonun belgesidir; biri değişirse
 * diğeri de güncellenmelidir (RolePermissionsTest bu tabloyu sabitler).
 */
public final class RolePermissions {

	private static final Map<StaffRole, Set<Permission>> MATRIX = Map.of(
			StaffRole.OWNER, EnumSet.allOf(Permission.class),
			StaffRole.BRANCH_MANAGER, EnumSet.of(CALENDAR_VIEW, RESERVATION_CREATE, RESERVATION_MOVE,
					RESERVATION_CANCEL, RESERVATION_STATUS_UPDATE, PITCH_BLOCK_MANAGE, PITCH_MANAGE, PRICE_MANAGE,
					REPORT_VIEW),
			StaffRole.RECEPTION, EnumSet.of(CALENDAR_VIEW, RESERVATION_CREATE, RESERVATION_MOVE,
					RESERVATION_CANCEL, RESERVATION_STATUS_UPDATE));

	private RolePermissions() {
	}

	public static boolean allows(StaffRole role, Permission permission) {
		return MATRIX.get(role).contains(permission);
	}

	public static Set<Permission> of(StaffRole role) {
		return EnumSet.copyOf(MATRIX.get(role));
	}

}
