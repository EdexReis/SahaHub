package com.sahahub.identity.domain;

import static com.sahahub.identity.domain.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

/** docs/YETKI_MATRISI.md ile birebir aynı olmalıdır. Matris değişirse bu test ve belge birlikte güncellenir. */
class RolePermissionsTest {

	@Test
	void ownerHasEverything() {
		assertThat(RolePermissions.of(StaffRole.OWNER)).isEqualTo(EnumSet.allOf(Permission.class));
	}

	@Test
	void branchManagerCannotManageStaffOrSeeAudit() {
		assertThat(RolePermissions.of(StaffRole.BRANCH_MANAGER)).isEqualTo(EnumSet.of(CALENDAR_VIEW,
				RESERVATION_CREATE, RESERVATION_MOVE, RESERVATION_CANCEL, RESERVATION_STATUS_UPDATE,
				PITCH_BLOCK_MANAGE, PITCH_MANAGE, PRICE_MANAGE, REPORT_VIEW, DISCOUNT_APPLY, PAYMENT_COLLECT,
				PAYMENT_REFUND, CASH_MANAGE, EXPENSE_MANAGE, TOURNAMENT_MANAGE));
	}

	@Test
	void receptionOnlyHandlesReservations() {
		assertThat(RolePermissions.of(StaffRole.RECEPTION)).isEqualTo(EnumSet.of(CALENDAR_VIEW, RESERVATION_CREATE,
				RESERVATION_MOVE, RESERVATION_CANCEL, RESERVATION_STATUS_UPDATE, PAYMENT_COLLECT, CASH_MANAGE));
	}

	@Test
	void membershipScope() {
		StaffMembership owner = StaffMembership.owner(1L, 10L, java.time.Instant.EPOCH);
		StaffMembership reception = StaffMembership.forBranch(2L, 10L, 100L, StaffRole.RECEPTION,
				java.time.Instant.EPOCH);
		assertThat(owner.covers(10L, 999L)).isTrue(); // işletmenin her şubesi
		assertThat(owner.covers(11L, 999L)).isFalse(); // başka işletme
		assertThat(reception.covers(10L, 100L)).isTrue();
		assertThat(reception.covers(10L, 101L)).isFalse(); // aynı işletmenin başka şubesi
	}

}
