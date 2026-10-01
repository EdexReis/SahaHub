package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Personelin rezervasyon işlemleri. Her metot önce kaydın şubesini veritabanından okur,
 * sonra AccessGuard ile kullanıcının o şubede ilgili izne sahip olduğunu doğrular.
 */
@Service
public class StaffReservationService {

	/** Personelin hızlı rezervasyon formundan gelen bilgiler. */
	public record CreateCommand(Long pitchId, LocalDateTime start, int durationMinutes, Channel channel,
			String customerEmail, String guestName, String guestPhone, String note) {
	}

	private final CatalogService catalog;
	private final AccessGuard guard;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;
	private final OccupancyService occupancy;
	private final ReservationViewFactory views;
	private final AppUserRepository users;
	private final ReservationPricingService pricing;
	private final ApplicationEventPublisher events;
	private final AuditService audit;
	private final Clock clock;

	public StaffReservationService(CatalogService catalog, AccessGuard guard, ReservationRepository reservations,
			ReservationWriter writer, OccupancyService occupancy, ReservationViewFactory views,
			AppUserRepository users, ReservationPricingService pricing, ApplicationEventPublisher events,
			AuditService audit, Clock clock) {
		this.catalog = catalog;
		this.guard = guard;
		this.reservations = reservations;
		this.writer = writer;
		this.occupancy = occupancy;
		this.views = views;
		this.users = users;
		this.pricing = pricing;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public String create(AppUserPrincipal user, Long branchId, CreateCommand cmd) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.RESERVATION_CREATE);
		PitchContext ctx = pitchInBranch(cmd.pitchId(), branchId);
		TimeRange play = validatePlay(ctx, cmd.start(), cmd.durationMinutes());

		Long customerId = resolveCustomer(cmd.customerEmail());
		Reservation r = Reservation.confirmedByStaff(bc.business().getId(), branchId, ctx.pitch().getId(), play,
				ctx.pitch().getBufferMinutes(), cmd.channel(), customerId, cmd.guestName(), cmd.guestPhone(),
				cmd.note(), user.id(), Instant.now(clock));
		Reservation saved = writer.persistNew(r, ctx);
		events.publishEvent(new BookingEvents.ReservationConfirmed(saved.getId()));
		audit.record(user.id(), bc.business().getId(), "RESERVATION_CREATED_BY_STAFF", "Reservation", saved.getId(),
				"code=" + saved.getCode() + ", channel=" + cmd.channel());
		return saved.getCode();
	}

	/**
	 * Rezervasyonu yeni saate/sahaya taşır. Tek transaction:
	 * eski doluluğu kapat → rezervasyonu güncelle → yeni doluluğu ekle.
	 * Son adımda çakışma çıkarsa transaction geri alınır; eski doluluk ve eski saat aynen kalır.
	 * Fiyat anlık görüntüsü değişmez (bkz. docs/DURUM_GECISLERI.md).
	 */
	@Transactional
	public void move(AppUserPrincipal user, String code, Long newPitchId, LocalDateTime newStart) {
		Reservation r = lockForStaff(user, code, Permission.RESERVATION_MOVE);
		PitchContext ctx = pitchInBranch(newPitchId, r.getBranchId());
		String before = r.getPitchId() + "@" + r.getStartsAt();
		TimeRange play = validatePlay(ctx, newStart, (int) r.playRange().minutes());
		Instant now = Instant.now(clock);

		var released = new BookingEvents.SlotReleased(r.getId(), r.getPitchId(), r.getStartsAt(),
				r.occupiedRange().end());
		occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
		r.reschedule(ctx.pitch().getId(), play, ctx.pitch().getBufferMinutes(), now);
		occupancy.occupy(r.getPitchId(), r.occupiedRange(), PitchOccupancy.Source.RESERVATION, r.getId());
		events.publishEvent(released); // eski saat boşaldı: bekleyen varsa teklif edilir
		audit.record(user.id(), r.getBusinessId(), "RESERVATION_MOVED", "Reservation", r.getId(),
				"code=" + code + ", from=" + before + ", to=" + r.getPitchId() + "@" + r.getStartsAt());
	}

	@Transactional
	public void cancel(AppUserPrincipal user, String code, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("İptal gerekçesini yazın.");
		}
		Reservation r = lockForStaff(user, code, Permission.RESERVATION_CANCEL);
		r.cancel(user.id(), reason, Instant.now(clock));
		occupancy.release(PitchOccupancy.Source.RESERVATION, r.getId());
		pricing.releaseCoupons(r.getId());
		events.publishEvent(new ReservationCancelled(r.getId(), false));
		events.publishEvent(new BookingEvents.SlotReleased(r.getId(), r.getPitchId(), r.getStartsAt(),
				r.occupiedRange().end()));
		audit.record(user.id(), r.getBusinessId(), "RESERVATION_CANCELLED_BY_STAFF", "Reservation", r.getId(),
				"code=" + code + ", reason=" + reason.strip());
	}

	@Transactional
	public void checkIn(AppUserPrincipal user, String code) {
		Reservation r = lockForStaff(user, code, Permission.RESERVATION_STATUS_UPDATE);
		r.checkIn(Instant.now(clock));
		audit.record(user.id(), r.getBusinessId(), "RESERVATION_CHECKED_IN", "Reservation", r.getId(), null);
	}

	@Transactional
	public void complete(AppUserPrincipal user, String code) {
		Reservation r = lockForStaff(user, code, Permission.RESERVATION_STATUS_UPDATE);
		r.complete(Instant.now(clock));
		audit.record(user.id(), r.getBusinessId(), "RESERVATION_COMPLETED", "Reservation", r.getId(), null);
	}

	@Transactional
	public void markNoShow(AppUserPrincipal user, String code) {
		Reservation r = lockForStaff(user, code, Permission.RESERVATION_STATUS_UPDATE);
		r.markNoShow(Instant.now(clock));
		audit.record(user.id(), r.getBusinessId(), "RESERVATION_NO_SHOW", "Reservation", r.getId(), null);
	}

	@Transactional(readOnly = true)
	public ReservationView view(AppUserPrincipal user, String code) {
		Reservation r = reservations.findByCode(code).orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(user, r.getBusinessId(), r.getBranchId(), Permission.CALENDAR_VIEW);
		return views.build(r);
	}

	// ------------------------------------------------------------------ yardımcılar

	/** E-posta girildiyse kayıtlı müşteriyi bulur; yoksa null (misafir). Seri oluşturma da kullanır. */
	Long resolveCustomer(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		return users.findByEmail(email.strip())
			.map(AppUser::getId)
			.orElseThrow(() -> new BusinessRuleException(
					"Bu e-postayla kayıtlı müşteri yok. E-postayı boş bırakıp ad ve telefon girin."));
	}

	private Reservation lockForStaff(AppUserPrincipal user, String code, Permission permission) {
		Reservation r = reservations.findByCodeForUpdate(code)
			.orElseThrow(() -> new NotFoundException("Rezervasyon"));
		guard.requireBranch(user, r.getBusinessId(), r.getBranchId(), permission);
		return r;
	}

	private PitchContext pitchInBranch(Long pitchId, Long branchId) {
		PitchContext ctx = catalog.pitchContext(pitchId);
		if (!ctx.branch().getId().equals(branchId) || !ctx.pitch().isActive()) {
			throw new NotFoundException("Saha");
		}
		return ctx;
	}

	/**
	 * Personel de geçmişe veya kapalı saate rezervasyon açamaz. Personel, müşterinin göremediği
	 * ara başlangıç saatlerini (ör. 21:30) seçebilir; süre 15 dakikanın katı olmalıdır.
	 */
	private TimeRange validatePlay(PitchContext ctx, LocalDateTime localStart, int durationMinutes) {
		if (localStart == null) {
			throw new BusinessRuleException("Başlangıç saatini seçin.");
		}
		if (durationMinutes < 30 || durationMinutes > 240 || durationMinutes % 15 != 0) {
			throw new BusinessRuleException("Süre 30 ile 240 dakika arasında ve 15 dakikanın katı olmalı.");
		}
		if (localStart.getMinute() % 15 != 0) {
			throw new BusinessRuleException("Başlangıç saati 15 dakikalık dilimlerden biri olmalı.");
		}
		Instant start = localStart.atZone(ctx.branch().zone()).toInstant();
		TimeRange play = new TimeRange(start, start.plus(Duration.ofMinutes(durationMinutes)));
		if (!start.isAfter(Instant.now(clock))) {
			throw new BusinessRuleException("Geçmiş bir saate rezervasyon yapılamaz.");
		}
		BranchSchedule schedule = catalog.schedule(ctx.branch(), localStart.toLocalDate().minusDays(1),
				localStart.toLocalDate());
		if (!schedule.isOpenDuring(play)) {
			throw new BusinessRuleException("Şube bu saatlerde kapalı.");
		}
		return play;
	}

}
