package com.sahahub.booking.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationSeries;
import com.sahahub.booking.domain.ReservationSeriesRepository;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.pricing.domain.PriceCalculator;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Düzenli rezervasyon (ör. "her salı 21:00, 8 hafta").
 *
 * <h2>Kurallar</h2>
 * <ol>
 * <li>Önce önizleme: her tarihin durumu (uygun / dolu / kapalı / geçmiş) ve fiyatı gösterilir.</li>
 * <li>"Tümünü oluştur" yalnızca bütün tarihler uygunsa çalışır.</li>
 * <li>"Seçili tarihleri oluştur" için kullanıcı tarihleri açıkça seçer; seçilmeyenler ekranda
 * "oluşturulmayacak" diye görünür. Hiçbir tarih sessizce atlanmaz.</li>
 * <li>Oluşturma tek transaction'dır: önizlemeden sonra bir tarih dolduysa hiçbir maç oluşturulmaz,
 * kullanıcı önizlemeyi yeniler.</li>
 * <li>Her maç ayrı rezervasyondur; tek tek veya "bu tarihten sonrası" olarak iptal edilebilir.</li>
 * <li>Seri sınırlıdır: en fazla {@code sahahub.booking.series-max-occurrences} maç.</li>
 * </ol>
 */
@Service
public class SeriesService {

	public enum Mode {
		ALL, SELECTED
	}

	public record SeriesCommand(Long pitchId, LocalDate firstDate, LocalTime start, int durationMinutes,
			int occurrences, Channel channel, String customerEmail, String guestName, String guestPhone, String note) {
	}

	public enum OccurrenceStatus {

		AVAILABLE("Uygun"), CONFLICT("Dolu"), CLOSED("Şube kapalı"), PAST("Geçmiş");

		private final String label;

		OccurrenceStatus(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public record Occurrence(int index, LocalDate date, ZonedDateTime start, ZonedDateTime end,
			OccurrenceStatus status, String reason, BigDecimal price, String currency) {

		public boolean available() {
			return status == OccurrenceStatus.AVAILABLE;
		}

	}

	public record Preview(List<Occurrence> items) {

		public long availableCount() {
			return items.stream().filter(Occurrence::available).count();
		}

		public boolean allAvailable() {
			return availableCount() == items.size();
		}

	}

	/** Rezervasyon panelinde gösterilen seri bilgisi. */
	public record SeriesInfo(Long seriesId, int index, int total, long openFromHere) {
	}

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final ReservationSeriesRepository seriesRepo;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;
	private final AvailabilityService availability;
	private final StaffReservationService staffReservations;
	private final OccupancyService occupancy;
	private final ReservationPricingService pricing;
	private final ApplicationEventPublisher events;
	private final AuditService audit;
	private final Clock clock;
	private final int maxOccurrences;

	public SeriesService(CatalogService catalog, AccessGuard guard, ReservationSeriesRepository seriesRepo,
			ReservationRepository reservations, ReservationWriter writer, AvailabilityService availability,
			StaffReservationService staffReservations, OccupancyService occupancy, ReservationPricingService pricing,
			ApplicationEventPublisher events, AuditService audit, Clock clock,
			@Value("${sahahub.booking.series-max-occurrences:26}") int maxOccurrences) {
		this.catalog = catalog;
		this.guard = guard;
		this.seriesRepo = seriesRepo;
		this.reservations = reservations;
		this.writer = writer;
		this.availability = availability;
		this.staffReservations = staffReservations;
		this.occupancy = occupancy;
		this.pricing = pricing;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
		this.maxOccurrences = maxOccurrences;
	}

	public int maxOccurrences() {
		return maxOccurrences;
	}

	@Transactional(readOnly = true)
	public Preview preview(AppUserPrincipal user, Long branchId, SeriesCommand cmd) {
		PitchContext ctx = authorize(user, branchId, cmd);
		return compute(ctx, cmd);
	}

	/**
	 * Seriyi oluşturur. SELECTED modunda chosenDates önizlemede kullanıcının işaretlediği tarihlerdir;
	 * her biri hâlâ uygun olmalıdır, değilse hiçbir şey oluşturulmaz.
	 *
	 * @return oluşan seri id'si
	 */
	@Transactional
	public Long create(AppUserPrincipal user, Long branchId, SeriesCommand cmd, Mode mode, List<LocalDate> chosenDates) {
		PitchContext ctx = authorize(user, branchId, cmd);
		Preview preview = compute(ctx, cmd);
		List<Occurrence> toCreate;
		if (mode == Mode.ALL) {
			if (!preview.allAvailable()) {
				throw new BusinessRuleException("Uygun olmayan tarihler var: " + unavailableDates(preview)
						+ ". Yalnızca uygun tarihleri seçerek oluşturabilirsiniz.");
			}
			toCreate = preview.items();
		}
		else {
			Set<LocalDate> chosen = new HashSet<>(chosenDates == null ? List.of() : chosenDates);
			if (chosen.isEmpty()) {
				throw new BusinessRuleException("Oluşturulacak en az bir tarih seçin.");
			}
			toCreate = new ArrayList<>();
			for (Occurrence o : preview.items()) {
				if (chosen.remove(o.date())) {
					if (!o.available()) {
						throw new BusinessRuleException(o.date() + " tarihi artık uygun değil (" + o.reason()
								+ "). Önizlemeyi yenileyin.");
					}
					toCreate.add(o);
				}
			}
			if (!chosen.isEmpty()) {
				throw new BusinessRuleException("Seçilen tarihlerden bazıları seriye ait değil.");
			}
		}

		Long customerId = staffReservations.resolveCustomer(cmd.customerEmail());
		Instant now = Instant.now(clock);
		ReservationSeries series = seriesRepo.save(new ReservationSeries(ctx.business().getId(), branchId,
				ctx.pitch().getId(), customerId, cmd.guestName(), cmd.guestPhone(), cmd.firstDate(), cmd.start(),
				cmd.durationMinutes(), cmd.occurrences(), cmd.channel(), cmd.note(), user.id(), now));
		for (Occurrence o : toCreate) {
			Reservation r = Reservation.confirmedByStaff(ctx.business().getId(), branchId, ctx.pitch().getId(),
					new TimeRange(o.start().toInstant(), o.end().toInstant()), ctx.pitch().getBufferMinutes(),
					cmd.channel(), customerId, cmd.guestName(), cmd.guestPhone(), cmd.note(), user.id(), now);
			r.attachToSeries(series.getId(), o.index());
			try {
				writer.persistNew(r, ctx);
			}
			catch (SlotUnavailableException ex) {
				// Önizlemeden sonra bu saat dolmuş: transaction geri alınır, hiçbir maç oluşmaz
				throw new BusinessRuleException(o.date() + " tarihi az önce doldu; seri oluşturulmadı. Önizlemeyi yenileyin.");
			}
		}
		events.publishEvent(new BookingEvents.SeriesCreated(series.getId(), toCreate.size()));
		audit.record(user.id(), ctx.business().getId(), "SERIES_CREATED", "ReservationSeries", series.getId(),
				"pitch=" + ctx.pitch().getId() + ", created=" + toCreate.size() + "/" + cmd.occurrences());
		return series.getId();
	}

	/** Seri içindeki bu maçı ve sonrakileri iptal eder; önceki maçlara dokunmaz. */
	@Transactional
	public int cancelFrom(AppUserPrincipal user, String code, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("İptal gerekçesini yazın.");
		}
		Reservation anchor = reservations.findByCode(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		if (anchor.getSeriesId() == null) {
			throw new BusinessRuleException("Bu rezervasyon bir serinin parçası değil.");
		}
		guard.requireBranch(user, anchor.getBusinessId(), anchor.getBranchId(), Permission.RESERVATION_CANCEL);
		Instant now = Instant.now(clock);
		List<Reservation> list = reservations.openInSeriesFromForUpdate(anchor.getSeriesId(), anchor.getStartsAt());
		for (Reservation r : list) {
			r.cancel(user.id(), reason.strip(), now);
			occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
			pricing.releaseCoupons(r.getId());
			events.publishEvent(new ReservationCancelled(r.getId(), false));
			events.publishEvent(new BookingEvents.SlotReleased(r.getId(), r.getPitchId(), r.getStartsAt(),
					r.occupiedRange().end()));
		}
		audit.record(user.id(), anchor.getBusinessId(), "SERIES_CANCELLED_FROM", "ReservationSeries",
				anchor.getSeriesId(), "from=" + code + ", count=" + list.size() + ", reason=" + reason.strip());
		return list.size();
	}

	/** Seri oluşturulduktan sonra takvimde açılacak ilk maçın kodu. */
	@Transactional(readOnly = true)
	public String firstCode(Long seriesId) {
		return reservations.findBySeriesIdOrderBySeriesIndex(seriesId).getFirst().getCode();
	}

	@Transactional(readOnly = true)
	public SeriesInfo info(AppUserPrincipal user, String code) {
		Reservation r = reservations.findByCode(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(user, r.getBusinessId(), r.getBranchId(), Permission.CALENDAR_VIEW);
		if (r.getSeriesId() == null) {
			return null;
		}
		List<Reservation> all = reservations.findBySeriesIdOrderBySeriesIndex(r.getSeriesId());
		long open = all.stream()
			.filter(x -> !x.getStartsAt().isBefore(r.getStartsAt()) && x.getStatus().isOpen())
			.count();
		return new SeriesInfo(r.getSeriesId(), r.getSeriesIndex(),
				seriesRepo.findById(r.getSeriesId()).orElseThrow().getOccurrences(), open);
	}

	// ------------------------------------------------------------------ yardımcılar

	private PitchContext authorize(AppUserPrincipal user, Long branchId, SeriesCommand cmd) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.RESERVATION_CREATE);
		if (cmd.pitchId() == null || cmd.firstDate() == null || cmd.start() == null) {
			throw new BusinessRuleException("Saha, ilk tarih ve saati seçin.");
		}
		if (cmd.occurrences() < 2 || cmd.occurrences() > maxOccurrences) {
			throw new BusinessRuleException("Tekrar sayısı 2 ile " + maxOccurrences + " arasında olmalı.");
		}
		if (cmd.durationMinutes() < 30 || cmd.durationMinutes() > 240 || cmd.durationMinutes() % 15 != 0
				|| cmd.start().getMinute() % 15 != 0) {
			throw new BusinessRuleException("Süre 30-240 dakika, başlangıç ve süre 15 dakikanın katı olmalı.");
		}
		PitchContext ctx = catalog.pitchContext(cmd.pitchId());
		if (!ctx.branch().getId().equals(branchId) || !ctx.pitch().isActive()) {
			throw new NotFoundException("Saha");
		}
		return ctx;
	}

	private Preview compute(PitchContext ctx, SeriesCommand cmd) {
		ZoneId zone = ctx.branch().zone();
		Instant now = Instant.now(clock);
		List<LocalDate> dates = ReservationSeries.dates(cmd.firstDate(), cmd.occurrences());
		BranchSchedule schedule = catalog.schedule(ctx.branch(), dates.getFirst().minusDays(1), dates.getLast());
		var rules = catalog.priceRules(ctx.pitch().getId());
		Duration buffer = ctx.pitch().buffer();
		List<Occurrence> items = new ArrayList<>();
		int index = 1;
		for (LocalDate d : dates) {
			ZonedDateTime start = d.atTime(cmd.start()).atZone(zone);
			ZonedDateTime end = start.plusMinutes(cmd.durationMinutes());
			TimeRange play = new TimeRange(start.toInstant(), end.toInstant());
			OccurrenceStatus status = OccurrenceStatus.AVAILABLE;
			String reason = null;
			if (!play.start().isAfter(now)) {
				status = OccurrenceStatus.PAST;
				reason = "Geçmiş tarih";
			}
			else if (!schedule.isOpenDuring(play)) {
				status = OccurrenceStatus.CLOSED;
				reason = "Şube bu saatte kapalı";
			}
			else {
				List<PitchOccupancy> busy = availability.occupanciesFor(List.of(ctx.pitch().getId()),
						play.extendEnd(buffer));
				if (!busy.isEmpty()) {
					status = OccurrenceStatus.CONFLICT;
					reason = busy.stream().anyMatch(o -> o.getSourceType() == PitchOccupancy.Source.BLOCK)
							? "Bakım/etkinlik kapatması" : "Başka rezervasyon var";
				}
			}
			var quote = PriceCalculator.quote(play, zone, ctx.pitch().getBaseHourlyPrice(), ctx.pitch().getCurrency(),
					rules);
			items.add(new Occurrence(index++, d, start, end, status, reason, quote.total(), quote.currency()));
		}
		return new Preview(items);
	}

	private static String unavailableDates(Preview p) {
		return String.join(", ", p.items().stream().filter(o -> !o.available()).map(o -> o.date().toString()).toList());
	}

}
