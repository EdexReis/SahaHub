package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.AvailabilityService.DayAvailability;
import com.sahahub.booking.app.AvailabilityService.DayStatus;
import com.sahahub.booking.app.AvailabilityService.SlotView;
import com.sahahub.booking.domain.CancellationPolicy;
import com.sahahub.booking.domain.HoldExpiredException;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.booking.domain.Slot;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Müşterinin kendi rezervasyonları: saat tutma, onaylama, iptal, listeleme.
 * Her işlemde rezervasyonun bu müşteriye ait olduğu kontrol edilir; başkasının
 * rezervasyon kodu bilinse bile "bulunamadı" döner.
 */
@Service
public class CustomerBookingService {

	/** Bir müşterinin aynı anda tutabileceği en fazla onaylanmamış saat (saat istiflemeyi önler). */
	static final int MAX_ACTIVE_HOLDS = 3;

	private final CatalogService catalog;
	private final AvailabilityService availability;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;
	private final OccupancyService occupancy;
	private final ReservationViewFactory views;
	private final HoldExpiryService expiry;
	private final ReservationPricingService pricing;
	private final PaymentStatusPort paymentStatus;
	private final ApplicationEventPublisher events;
	private final AuditService audit;
	private final Clock clock;

	public CustomerBookingService(CatalogService catalog, AvailabilityService availability,
			ReservationRepository reservations, ReservationWriter writer, OccupancyService occupancy,
			ReservationViewFactory views, HoldExpiryService expiry, ReservationPricingService pricing,
			PaymentStatusPort paymentStatus, ApplicationEventPublisher events, AuditService audit, Clock clock) {
		this.catalog = catalog;
		this.availability = availability;
		this.reservations = reservations;
		this.writer = writer;
		this.occupancy = occupancy;
		this.views = views;
		this.expiry = expiry;
		this.pricing = pricing;
		this.paymentStatus = paymentStatus;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Seçilen saati müşteri adına geçici olarak tutar ve rezervasyon kodunu döner.
	 * Ekrandaki uygunluk bilgisi eski olabilir; kesin karar OccupancyService'teki
	 * veritabanı kısıtına aittir.
	 */
	@Transactional
	public String hold(AppUserPrincipal user, Long pitchId, Instant start) {
		PitchContext ctx = catalog.publicPitch(pitchId);
		if (reservations.countByCustomerIdAndStatus(user.id(), ReservationStatus.HELD) >= MAX_ACTIVE_HOLDS) {
			throw new BusinessRuleException("Aynı anda en fazla " + MAX_ACTIVE_HOLDS
					+ " saat tutabilirsiniz. Önce bekleyen rezervasyonunuzu onaylayın veya bırakın.");
		}
		SlotView slot = findSlot(ctx, start);
		Instant now = Instant.now(clock);
		Reservation reservation = Reservation.holdForCustomer(ctx.business().getId(), ctx.branch().getId(),
				pitchId, new TimeRange(slot.start(), slot.localEnd().toInstant()),
				ctx.pitch().getBufferMinutes(), user.id(), Duration.ofMinutes(ctx.branch().getHoldMinutes()), now);
		return writer.persistNew(reservation, ctx).getCode();
	}

	/**
	 * İstenen başlangıcın bu sahada geçerli ve uygun bir saat olduğunu doğrular.
	 * Gece yarısından sonraki saatler bir önceki iş gününe ait olabileceği için iki güne bakılır.
	 */
	private SlotView findSlot(PitchContext ctx, Instant start) {
		LocalDate localDate = start.atZone(ctx.branch().zone()).toLocalDate();
		for (LocalDate day : new LocalDate[] { localDate, localDate.minusDays(1) }) {
			DayAvailability da = availability.forDay(ctx, day);
			if (da.status() == DayStatus.BEYOND_HORIZON) {
				throw new BusinessRuleException("Bu tarih için henüz rezervasyon alınmıyor.");
			}
			Optional<SlotView> match = da.slots().stream().filter(s -> s.start().equals(start)).findFirst();
			if (match.isPresent()) {
				Slot.State state = match.get().state();
				return switch (state) {
					case AVAILABLE -> match.get();
					case PAST -> throw new BusinessRuleException("Geçmiş bir saate rezervasyon yapılamaz.");
					case TAKEN, BLOCKED -> throw new SlotUnavailableException();
				};
			}
		}
		throw new BusinessRuleException("Bu saat bu sahada rezervasyona açık değil.");
	}

	/**
	 * Tutulan saati onaylar. Tutma süresi dolmuşsa rezervasyon EXPIRED yapılır, saha
	 * serbest bırakılır ve hata fırlatılır. noRollbackFor sayesinde bu temizlik,
	 * hata fırlatılmasına rağmen kaydedilir.
	 */
	@Transactional(noRollbackFor = HoldExpiredException.class)
	public void confirm(AppUserPrincipal user, String code) {
		Reservation r = lockOwned(user, code);
		Instant now = Instant.now(clock);
		if (r.isHoldExpired(now)) {
			expiry.expire(r, now);
			throw new HoldExpiredException();
		}
		// Kapora kuralı varsa onay ödeme bildirimiyle gelir; ödemesiz onay düğmesiyle atlanamaz
		if (!paymentStatus.depositCovered(r)) {
			throw new BusinessRuleException("Onay için önce kaporayı ödeyin.");
		}
		r.confirm(now);
		events.publishEvent(new BookingEvents.ReservationConfirmed(r.getId()));
	}

	@Transactional
	public void cancel(AppUserPrincipal user, String code) {
		Reservation r = lockOwned(user, code);
		Instant now = Instant.now(clock);
		PitchContext ctx = catalog.pitchContext(r.getPitchId());
		if (!CancellationPolicy.customerMayCancel(r.getStatus(), r.getStartsAt(),
				ctx.branch().customerCancelCutoff(), now)) {
			throw new BusinessRuleException("Çevrim içi iptal süresi geçti (maçtan "
					+ ctx.branch().getCustomerCancelCutoffHours() + " saat öncesine kadar). İptal için şubeyi arayın"
					+ (ctx.branch().getPhone() == null ? "." : ": " + ctx.branch().getPhone()));
		}
		boolean wasHeld = r.getStatus() == ReservationStatus.HELD;
		r.cancel(user.id(), wasHeld ? "Müşteri tutulan saati bıraktı" : "Müşteri iptali", now);
		occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
		pricing.releaseCoupons(r.getId());
		// Commit sonrası ödeme modülü, süresi içindeki iptalde çevrim içi ödemeyi iade eder
		events.publishEvent(new ReservationCancelled(r.getId(), true));
		events.publishEvent(new BookingEvents.SlotReleased(r.getId(), r.getPitchId(), r.getStartsAt(),
				r.occupiedRange().end()));
		if (!wasHeld) {
			audit.record(user.id(), r.getBusinessId(), "RESERVATION_CANCELLED_BY_CUSTOMER", "Reservation", r.getId(),
					"code=" + r.getCode());
		}
	}

	@Transactional(readOnly = true)
	public ReservationView view(AppUserPrincipal user, String code) {
		Reservation r = reservations.findByCode(code)
			.filter(x -> user.id().equals(x.getCustomerId()))
			.orElseThrow(() -> new NotFoundException("Rezervasyon"));
		return views.build(r);
	}

	private static final Set<ReservationStatus> OPEN = EnumSet.of(ReservationStatus.HELD,
			ReservationStatus.CONFIRMED);

	/** Yaklaşan (bitmemiş ve açık) rezervasyonlar; en yakın maç önce, en fazla 50. */
	@Transactional(readOnly = true)
	public List<ReservationView> upcoming(AppUserPrincipal user) {
		return reservations.findUpcoming(user.id(), Instant.now(clock), OPEN, Pageable.ofSize(50))
			.stream()
			.map(views::build)
			.toList();
	}

	/** Geçmiş, iptal edilmiş ve süresi dolmuş rezervasyonlar; sayfalı. */
	@Transactional(readOnly = true)
	public Page<ReservationView> history(AppUserPrincipal user, Pageable pageable) {
		return reservations.findHistory(user.id(), Instant.now(clock), OPEN, pageable).map(views::build);
	}

	private Reservation lockOwned(AppUserPrincipal user, String code) {
		return reservations.findByCodeForUpdate(code)
			.filter(r -> user.id().equals(r.getCustomerId()))
			.orElseThrow(() -> new NotFoundException("Rezervasyon"));
	}

}
