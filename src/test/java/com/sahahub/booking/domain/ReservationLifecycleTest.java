package com.sahahub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.TimeRange;

class ReservationLifecycleTest {

	static final Instant NOW = Instant.parse("2026-03-03T10:00:00Z");
	static final TimeRange PLAY = new TimeRange(NOW.plus(Duration.ofHours(8)), NOW.plus(Duration.ofHours(9)));

	static Reservation held() {
		return Reservation.holdForCustomer(1L, 1L, 1L, PLAY, 0, 7L, Duration.ofMinutes(10), NOW);
	}

	static Reservation confirmed() {
		return Reservation.confirmedByStaff(1L, 1L, 1L, PLAY, 0, Channel.PHONE, null, "Ali", null, null, 9L, NOW);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationStatus.class, names = { "CANCELLED", "COMPLETED", "NO_SHOW", "EXPIRED" })
	void finalStatesAllowNoTransition(ReservationStatus status) {
		assertThat(status.allowedNext()).isEmpty();
	}

	@Test
	void onlyActiveStatesOccupyThePitch() {
		assertThat(ReservationStatus.CANCELLED.occupiesPitch()).isFalse();
		assertThat(ReservationStatus.EXPIRED.occupiesPitch()).isFalse();
		assertThat(ReservationStatus.HELD.occupiesPitch()).isTrue();
		assertThat(ReservationStatus.CONFIRMED.occupiesPitch()).isTrue();
	}

	@Test
	void heldCanBeConfirmedBeforeExpiry() {
		Reservation r = held();
		r.confirm(NOW.plus(Duration.ofMinutes(9)));
		assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(r.getHoldExpiresAt()).isNull();
	}

	@Test
	void holdExpiresExactlyAtExpiryInstant() {
		Reservation r = held();
		assertThat(r.isHoldExpired(NOW.plus(Duration.ofMinutes(10)).minusMillis(1))).isFalse();
		assertThat(r.isHoldExpired(NOW.plus(Duration.ofMinutes(10)))).isTrue();
		assertThatThrownBy(() -> r.confirm(NOW.plus(Duration.ofMinutes(10)))).isInstanceOf(HoldExpiredException.class);
		r.expire(NOW.plus(Duration.ofMinutes(10)));
		assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
	}

	@Test
	void cannotExpireAConfirmedReservation() {
		Reservation r = confirmed();
		assertThatThrownBy(() -> r.expire(NOW.plus(Duration.ofDays(1)))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void completedCannotBeCancelled() {
		Reservation r = confirmed();
		r.complete(PLAY.start().plusSeconds(60));
		assertThatThrownBy(() -> r.cancel(1L, "x", PLAY.start().plusSeconds(120)))
			.isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void noShowOnlyAfterStart_andNotAfterCheckIn() {
		Reservation r = confirmed();
		assertThatThrownBy(() -> r.markNoShow(PLAY.start().minusSeconds(1))).isInstanceOf(BusinessRuleException.class);
		r.checkIn(PLAY.start().minus(Duration.ofMinutes(30)));
		assertThatThrownBy(() -> r.markNoShow(PLAY.start().plusSeconds(60))).isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void checkInWindowIsOneHourBeforeStartUntilEnd() {
		Reservation r = confirmed();
		assertThatThrownBy(() -> r.checkIn(PLAY.start().minus(Duration.ofMinutes(61))))
			.isInstanceOf(BusinessRuleException.class);
		r.checkIn(PLAY.start().minus(Duration.ofMinutes(60)));
		assertThat(r.getCheckedInAt()).isNotNull();
	}

	@Test
	void startedMatchCannotBeMoved() {
		Reservation r = confirmed();
		TimeRange later = new TimeRange(PLAY.start().plus(Duration.ofHours(2)), PLAY.end().plus(Duration.ofHours(2)));
		assertThatThrownBy(() -> r.reschedule(1L, later, 0, PLAY.start())).isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void occupiedRangeIncludesBuffer() {
		Reservation r = Reservation.confirmedByStaff(1L, 1L, 1L, PLAY, 15, Channel.PHONE, null, "Ali", null, null, 9L,
				NOW);
		assertThat(r.occupiedRange().end()).isEqualTo(PLAY.end().plus(Duration.ofMinutes(15)));
		assertThat(r.playRange()).isEqualTo(PLAY);
	}

	@Test
	void staffBookingNeedsCustomerOrGuestName() {
		assertThatThrownBy(() -> Reservation.confirmedByStaff(1L, 1L, 1L, PLAY, 0, Channel.PHONE, null, " ", null,
				null, 9L, NOW))
			.isInstanceOf(BusinessRuleException.class);
	}

	@Test
	void cancellationDeadlineBoundaryIsInclusive() {
		Duration cutoff = Duration.ofHours(24);
		Instant deadline = PLAY.start().minus(cutoff);
		assertThat(CancellationPolicy.customerMayCancel(ReservationStatus.CONFIRMED, PLAY.start(), cutoff, deadline))
			.isTrue();
		assertThat(CancellationPolicy.customerMayCancel(ReservationStatus.CONFIRMED, PLAY.start(), cutoff,
				deadline.plusMillis(1))).isFalse();
		assertThat(CancellationPolicy.customerMayCancel(ReservationStatus.HELD, PLAY.start(), cutoff,
				PLAY.start().minusSeconds(1))).isTrue();
		assertThat(CancellationPolicy.customerMayCancel(ReservationStatus.COMPLETED, PLAY.start(), cutoff,
				deadline.minusSeconds(1))).isFalse();
	}

}
