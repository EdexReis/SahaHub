package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.CalendarView.Column;
import com.sahahub.booking.app.CalendarView.Item;
import com.sahahub.booking.app.CalendarView.Kind;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.booking.domain.Slot;
import com.sahahub.booking.domain.SlotCalculator;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.business.domain.PitchBlockRepository;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/** Personel takvimini (günlük: sahalar yan yana, haftalık: tek saha 7 gün) hazırlar. */
@Service
@Transactional(readOnly = true)
public class StaffCalendarService {

	private static final Set<ReservationStatus> SHOWN = EnumSet.of(ReservationStatus.HELD,
			ReservationStatus.CONFIRMED, ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW);
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEE d", Locale.forLanguageTag("tr"));
	private static final Duration ROW = Duration.ofMinutes(CalendarView.MINUTES_PER_ROW);

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final ReservationRepository reservations;
	private final PitchBlockRepository blocks;
	private final AvailabilityService availability;
	private final AppUserRepository users;
	private final PaymentStatusPort paymentStatus;
	private final Clock clock;

	public StaffCalendarService(CatalogService catalog, AccessGuard guard, ReservationRepository reservations,
			PitchBlockRepository blocks, AvailabilityService availability, AppUserRepository users,
			PaymentStatusPort paymentStatus, Clock clock) {
		this.catalog = catalog;
		this.guard = guard;
		this.reservations = reservations;
		this.blocks = blocks;
		this.availability = availability;
		this.users = users;
		this.paymentStatus = paymentStatus;
		this.clock = clock;
	}

	/** Bir şubenin tüm sahaları, tek gün. day boşsa şubenin saatine göre bugün. */
	public CalendarView day(AppUserPrincipal user, Long branchId, LocalDate requestedDay) {
		BranchContext ctx = catalog.branchContext(branchId);
		StaffRole role = guard.requireBranch(user, ctx.business().getId(), branchId, Permission.CALENDAR_VIEW);
		ZoneId zone = ctx.branch().zone();
		LocalDate day = requestedDay != null ? requestedDay : LocalDate.now(clock.withZone(zone));
		Instant now = Instant.now(clock);
		BranchSchedule schedule = catalog.schedule(ctx.branch(), day, day);
		Optional<TimeRange> window = schedule.windowFor(day);
		TimeRange grid = window.map(StaffCalendarService::roundToHours).orElse(defaultGrid(day, zone));

		List<Pitch> pitches = catalog.activePitches(branchId);
		Data data = load(branchId, pitches, grid);
		List<Column> columns = new ArrayList<>();
		for (Pitch p : pitches) {
			String subtitle = p.getCapacityPlayers() / 2 + "+" + p.getCapacityPlayers() / 2 + " · "
					+ (p.isIndoor() ? "Kapalı saha" : "Açık saha");
			columns.add(column(p, day, p.getName(), subtitle, grid, window, data, zone, now));
		}
		List<Reservation> dayReservations = data.reservations.stream()
			.filter(r -> schedule.businessDayOf(r.playRange()).map(day::equals).orElse(true))
			.toList();
		return new CalendarView(branchId, ctx.branch().getName(), ctx.business().getName(), role,
				CalendarView.Mode.DAY, day, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), null,
				pitchOptions(pitches), window.isPresent(), rowCount(grid), timeLabels(grid, zone), columns,
				summary(dayReservations, columns, pitches), upcoming(dayReservations, pitches, data, zone, now),
				guard.can(user, ctx.business().getId(), branchId, Permission.PITCH_BLOCK_MANAGE), nowRow(grid, now));
	}

	/** Tek saha, pazartesiden başlayan 7 gün. */
	public CalendarView week(AppUserPrincipal user, Long branchId, Long pitchId, LocalDate requestedDay) {
		BranchContext ctx = catalog.branchContext(branchId);
		LocalDate anyDay = requestedDay != null ? requestedDay : LocalDate.now(clock.withZone(ctx.branch().zone()));
		StaffRole role = guard.requireBranch(user, ctx.business().getId(), branchId, Permission.CALENDAR_VIEW);
		List<Pitch> pitches = catalog.activePitches(branchId);
		if (pitches.isEmpty()) {
			return day(user, branchId, anyDay);
		}
		Pitch pitch = pitchId == null ? pitches.getFirst()
				: pitches.stream()
					.filter(p -> p.getId().equals(pitchId))
					.findFirst()
					.orElseThrow(() -> new NotFoundException("Saha"));
		ZoneId zone = ctx.branch().zone();
		Instant now = Instant.now(clock);
		LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		BranchSchedule schedule = catalog.schedule(ctx.branch(), monday, monday.plusDays(6));

		// Tüm günler aynı satırları paylaşsın diye en erken açılış ve en geç kapanış (gün başına göre dakika)
		long minStart = Long.MAX_VALUE;
		long maxEnd = Long.MIN_VALUE;
		for (int i = 0; i < 7; i++) {
			LocalDate d = monday.plusDays(i);
			Optional<TimeRange> w = schedule.windowFor(d);
			if (w.isPresent()) {
				Instant midnight = d.atStartOfDay(zone).toInstant();
				TimeRange rounded = roundToHours(w.get());
				minStart = Math.min(minStart, Duration.between(midnight, rounded.start()).toMinutes());
				maxEnd = Math.max(maxEnd, Duration.between(midnight, rounded.end()).toMinutes());
			}
		}
		if (minStart == Long.MAX_VALUE) {
			minStart = 8 * 60;
			maxEnd = 24 * 60;
		}
		TimeRange whole = new TimeRange(monday.atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(minStart)),
				monday.plusDays(6).atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(maxEnd)));
		Data data = load(branchId, List.of(pitch), whole);

		List<Column> columns = new ArrayList<>();
		TimeRange firstGrid = null;
		for (int i = 0; i < 7; i++) {
			LocalDate d = monday.plusDays(i);
			Instant midnight = d.atStartOfDay(zone).toInstant();
			TimeRange grid = new TimeRange(midnight.plus(Duration.ofMinutes(minStart)),
					midnight.plus(Duration.ofMinutes(maxEnd)));
			if (firstGrid == null) {
				firstGrid = grid;
			}
			columns.add(column(pitch, d, d.format(DAY_TITLE), null, grid, schedule.windowFor(d), data, zone, now));
		}
		List<Reservation> weekReservations = data.reservations;
		Integer nowRow = null;
		for (Column c : columns) {
			if (c.today()) {
				nowRow = nowRow(new TimeRange(c.day().atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(minStart)),
						c.day().atStartOfDay(zone).toInstant().plus(Duration.ofMinutes(maxEnd))), now);
			}
		}
		return new CalendarView(branchId, ctx.branch().getName(), ctx.business().getName(), role,
				CalendarView.Mode.WEEK, anyDay, monday, pitch.getId(), pitchOptions(pitches), true,
				rowCount(firstGrid), timeLabels(firstGrid, zone), columns,
				summary(weekReservations, columns, List.of(pitch)), List.of(),
				guard.can(user, ctx.business().getId(), branchId, Permission.PITCH_BLOCK_MANAGE), nowRow);
	}

	// ------------------------------------------------------------------ yardımcılar

	/** Takvimde gösterilecek veriler tek seferde yüklenir (sahada sorgu döngüsü yok). */
	private record Data(List<Reservation> reservations, List<PitchBlock> blocks, List<PitchOccupancy> occupancies,
			Map<Long, String> customerNames, Map<Long, PaymentStatusPort.Badge> badges) {
	}

	private Data load(Long branchId, List<Pitch> pitches, TimeRange range) {
		List<Long> pitchIds = pitches.stream().map(Pitch::getId).toList();
		if (pitchIds.isEmpty()) {
			return new Data(List.of(), List.of(), List.of(), Map.of(), Map.of());
		}
		List<Reservation> res = reservations.findForCalendar(branchId, range.start(), range.end(), SHOWN)
			.stream()
			.filter(r -> pitchIds.contains(r.getPitchId()))
			.toList();
		List<PitchBlock> blk = blocks.findActiveOverlapping(pitchIds, range.start(), range.end());
		// Hazırlık süresi dahil kesişenleri görmek için aralığı biraz genişlet
		List<PitchOccupancy> occ = availability.occupanciesFor(pitchIds,
				new TimeRange(range.start().minus(Duration.ofHours(2)), range.end().plus(Duration.ofHours(2))));
		List<Long> customerIds = res.stream().map(Reservation::getCustomerId).filter(id -> id != null).toList();
		Map<Long, String> names = customerIds.isEmpty() ? Map.of()
				: users.findAllById(customerIds).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
		return new Data(res, blk, occ, names, paymentStatus.badges(res));
	}

	private Column column(Pitch pitch, LocalDate day, String title, String subtitle, TimeRange grid,
			Optional<TimeRange> openWindow, Data data, ZoneId zone, Instant now) {
		List<Item> items = new ArrayList<>();
		for (Reservation r : data.reservations) {
			if (!r.getPitchId().equals(pitch.getId()) || !r.playRange().overlaps(grid)) {
				continue;
			}
			String name = r.getCustomerId() != null ? data.customerNames.getOrDefault(r.getCustomerId(), "Müşteri")
					: r.getGuestName();
			PaymentStatusPort.Badge badge = data.badges.get(r.getId());
			items.add(new Item(Kind.RESERVATION, row(grid, r.getStartsAt()), span(grid, r.playRange()), r.getCode(),
					name, time(r.playRange(), zone), r.getStatus(), r.getChannel(), r.getCheckedInAt() != null,
					pitch.getId(), null, null, badge == null ? null : badge.state(),
					badge == null ? null : badge.label()));
			if (r.getBufferMinutes() > 0 && r.occupiedRange().end().isAfter(grid.start())) {
				TimeRange buffer = new TimeRange(r.getEndsAt(), r.occupiedRange().end());
				if (buffer.overlaps(grid)) {
					items.add(item(Kind.BUFFER, grid, buffer, r.getCode(), "Hazırlık", null, null, null, false,
							pitch.getId(), null));
				}
			}
		}
		for (PitchBlock b : data.blocks) {
			if (b.getPitchId().equals(pitch.getId()) && b.range().overlaps(grid)) {
				items.add(new Item(Kind.BLOCK, row(grid, b.getStartsAt()), span(grid, b.range()), null,
						b.getReason().label(), b.getNote() == null ? time(b.range(), zone) : b.getNote(), null, null,
						false, pitch.getId(), null, b.getId(), null, null));
			}
		}
		if (openWindow.isPresent()) {
			List<SlotCalculator.Busy> busy = data.occupancies.stream()
				.filter(o -> o.getPitchId().equals(pitch.getId()))
				.map(o -> new SlotCalculator.Busy(o.range(), o.getSourceType()))
				.toList();
			for (Slot s : SlotCalculator.slots(openWindow.get(), AvailabilityService.slotConfig(pitch), busy, now)) {
				if (s.isAvailable()) {
					items.add(item(Kind.FREE, grid, s.play(), null, "Boş", time(s.play(), zone), null, null, false,
							pitch.getId(), s.play().start().atZone(zone).toLocalDateTime()));
				}
			}
		}
		items.sort(Comparator.comparingInt(Item::rowStart));
		boolean today = day.equals(LocalDate.ofInstant(now, zone));
		return new Column(pitch.getId(), day, title, subtitle, openWindow.isEmpty(), today, items);
	}

	private static Item item(Kind kind, TimeRange grid, TimeRange range, String code, String title, String subtitle,
			ReservationStatus status, Channel channel, boolean checkedIn, Long pitchId, LocalDateTime start) {
		return new Item(kind, row(grid, range.start()), span(grid, range), code, title, subtitle, status, channel,
				checkedIn, pitchId, start, null, null, null);
	}

	/** Anın ızgaradaki satır numarası (1'den başlar), ızgara dışına taşanlar kenara kırpılır. */
	static int row(TimeRange grid, Instant at) {
		Instant clamped = at.isBefore(grid.start()) ? grid.start() : at.isAfter(grid.end()) ? grid.end() : at;
		return (int) (Duration.between(grid.start(), clamped).toMinutes() / CalendarView.MINUTES_PER_ROW) + 1;
	}

	static int span(TimeRange grid, TimeRange range) {
		return Math.max(1, row(grid, range.end()) - row(grid, range.start()));
	}

	private static int rowCount(TimeRange grid) {
		return (int) (grid.minutes() / CalendarView.MINUTES_PER_ROW);
	}

	private static Integer nowRow(TimeRange grid, Instant now) {
		return grid.start().isBefore(now) && now.isBefore(grid.end()) ? row(grid, now) : null;
	}

	private static List<CalendarView.TimeLabel> timeLabels(TimeRange grid, ZoneId zone) {
		List<CalendarView.TimeLabel> labels = new ArrayList<>();
		for (Instant t = grid.start(); t.isBefore(grid.end()); t = t.plus(Duration.ofHours(1))) {
			labels.add(new CalendarView.TimeLabel(t.atZone(zone).format(HM), row(grid, t)));
		}
		return labels;
	}

	private static TimeRange roundToHours(TimeRange window) {
		Instant start = window.start().truncatedTo(ChronoUnit.HOURS);
		Instant end = window.end().truncatedTo(ChronoUnit.HOURS);
		if (end.isBefore(window.end())) {
			end = end.plus(Duration.ofHours(1));
		}
		return new TimeRange(start, end);
	}

	private static TimeRange defaultGrid(LocalDate day, ZoneId zone) {
		return new TimeRange(day.atTime(8, 0).atZone(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
	}

	private static String time(TimeRange range, ZoneId zone) {
		return range.start().atZone(zone).format(HM) + "–" + range.end().atZone(zone).format(HM);
	}

	private static List<CalendarView.PitchOption> pitchOptions(List<Pitch> pitches) {
		return pitches.stream().map(p -> new CalendarView.PitchOption(p.getId(), p.getName())).toList();
	}

	private static CalendarView.Summary summary(List<Reservation> res, List<Column> columns, List<Pitch> pitches) {
		int held = 0;
		int checkedIn = 0;
		int active = 0;
		BigDecimal booked = BigDecimal.ZERO;
		for (Reservation r : res) {
			if (r.getStatus() == ReservationStatus.HELD) {
				held++;
			}
			if (r.getCheckedInAt() != null) {
				checkedIn++;
			}
			if (r.getStatus() != ReservationStatus.HELD) {
				active++;
				booked = booked.add(r.getTotalAmount());
			}
		}
		int free = (int) columns.stream().flatMap(c -> c.items().stream()).filter(i -> i.kind() == Kind.FREE).count();
		String currency = pitches.isEmpty() ? "TRY" : pitches.getFirst().getCurrency();
		return new CalendarView.Summary(active, held, checkedIn, free, booked, currency);
	}

	private static List<CalendarView.Upcoming> upcoming(List<Reservation> res, List<Pitch> pitches, Data data,
			ZoneId zone, Instant now) {
		Map<Long, String> pitchNames = new HashMap<>();
		pitches.forEach(p -> pitchNames.put(p.getId(), p.getName()));
		return res.stream()
			.filter(r -> r.getStatus().isOpen() && r.getEndsAt().isAfter(now))
			.sorted(Comparator.comparing(Reservation::getStartsAt))
			.limit(6)
			.map(r -> new CalendarView.Upcoming(r.getCode(), r.getStartsAt().atZone(zone), r.getEndsAt().atZone(zone),
					pitchNames.get(r.getPitchId()),
					r.getCustomerId() != null ? data.customerNames.getOrDefault(r.getCustomerId(), "Müşteri")
							: r.getGuestName(),
					r.getStatus(), r.getCheckedInAt() != null))
			.toList();
	}

}
