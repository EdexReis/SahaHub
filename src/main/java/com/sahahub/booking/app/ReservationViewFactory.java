package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

import com.sahahub.booking.domain.CancellationPolicy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationPriceLineRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;

/** Reservation entity'sinden ekran modeli (ReservationView) üretir. */
@Component
class ReservationViewFactory {

	private final CatalogService catalog;
	private final ReservationPriceLineRepository priceLines;
	private final AppUserRepository users;
	private final Clock clock;

	ReservationViewFactory(CatalogService catalog, ReservationPriceLineRepository priceLines, AppUserRepository users,
			Clock clock) {
		this.catalog = catalog;
		this.priceLines = priceLines;
		this.users = users;
		this.clock = clock;
	}

	ReservationView build(Reservation r) {
		PitchContext ctx = catalog.pitchContext(r.getPitchId());
		ZoneId zone = ctx.branch().zone();
		Instant now = Instant.now(clock);
		var lines = priceLines.findByReservationIdOrderByLineNo(r.getId())
			.stream()
			.map(l -> new ReservationView.Line(l.getLabel(), l.getStartsAt().atZone(zone), l.getEndsAt().atZone(zone),
					l.getMinutes(), l.getHourlyRate(), l.getAmount()))
			.toList();
		AppUser customer = r.getCustomerId() == null ? null : users.findById(r.getCustomerId()).orElse(null);
		String name = customer != null ? customer.getFullName() : r.getGuestName();
		String phone = customer != null ? customer.getPhone() : r.getGuestPhone();
		var cutoff = ctx.branch().customerCancelCutoff();
		LocalDate startDate = r.getStartsAt().atZone(zone).toLocalDate();
		LocalDate businessDay = catalog.schedule(ctx.branch(), startDate.minusDays(1), startDate)
			.businessDayOf(r.playRange())
			.orElse(startDate);
		return new ReservationView(r.getCode(), r.getStatus(), r.getChannel(), r.getPitchId(), ctx.pitch().getName(),
				ctx.branch().getId(), ctx.branch().getName(), ctx.business().getName(),
				ctx.branch().getAddressLine() + ", " + ctx.branch().getDistrict() + " / " + ctx.branch().getCity(),
				r.getStartsAt().atZone(zone), r.getEndsAt().atZone(zone), (int) r.playRange().minutes(),
				r.getBufferMinutes(), r.getTotalAmount(), r.getCurrency(), lines, at(r.getHoldExpiresAt(), zone),
				CancellationPolicy.customerMayCancel(r.getStatus(), r.getStartsAt(), cutoff, now),
				CancellationPolicy.customerDeadline(r.getStartsAt(), cutoff).atZone(zone), name, phone, r.getNote(),
				at(r.getCheckedInAt(), zone), r.getCancelReason(), r.getCreatedAt().atZone(zone), businessDay,
				new ReservationView.Actions(r.canCheckIn(now), r.canComplete(now), r.canMarkNoShow(now),
						r.canReschedule(now), r.canBeCancelledByStaff(now)),
				r.checkInOpensAt().atZone(zone));
	}

	private static ZonedDateTime at(Instant instant, ZoneId zone) {
		return instant == null ? null : instant.atZone(zone);
	}

}
