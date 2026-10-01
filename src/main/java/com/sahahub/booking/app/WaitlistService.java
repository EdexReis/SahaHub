package com.sahahub.booking.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.booking.app.AvailabilityService.DayAvailability;
import com.sahahub.booking.app.AvailabilityService.SlotView;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.Slot;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.booking.domain.WaitlistEntry;
import com.sahahub.booking.domain.WaitlistRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;

/**
 * Dolu saat için bekleme listesi.
 *
 * <h2>Teklif nasıl çalışır?</h2>
 * Saat boşalınca (iptal, süre dolumu, taşıma) sıradaki ilk müşteri adına, kısa süreli bir
 * <b>geçici tutma (HELD rezervasyon)</b> açılır. Saat bu tutmayla sahada meşgul olduğu için:
 * <ul>
 * <li>aynı boşluk aynı anda iki kişiye teklif edilemez (EXCLUDE kısıtı + "slot başına tek açık teklif"
 * kısmi tekil indeksi),</li>
 * <li>başka bir müşteri o arada saati alamaz,</li>
 * <li>müşteri teklifi normal onay akışıyla (kapora varsa ödemeyle) kabul eder.</li>
 * </ul>
 * Teklif süresi dolarsa veya müşteri bırakırsa tutma serbest kalır, aynı olay yeniden tetiklenir ve
 * sıradaki kişiye geçilir.
 */
@Service
public class WaitlistService {

	private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);
	static final int MAX_ACTIVE_PER_CUSTOMER = 5;

	/** Müşterinin "bekleme listem" görünümü. */
	public record EntryView(Long id, WaitlistEntry.Status status, String pitchName, String branchName,
			ZonedDateTime start, ZonedDateTime end, long position, String offerCode) {
	}

	private final WaitlistRepository waitlist;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;
	private final CatalogService catalog;
	private final AvailabilityService availability;
	private final ApplicationEventPublisher events;
	private final TransactionTemplate newTx;
	private final Clock clock;
	private final Duration offerDuration;

	public WaitlistService(WaitlistRepository waitlist, ReservationRepository reservations, ReservationWriter writer,
			CatalogService catalog, AvailabilityService availability, ApplicationEventPublisher events,
			PlatformTransactionManager txManager, Clock clock,
			@Value("${sahahub.waitlist.offer-minutes:15}") int offerMinutes) {
		this.waitlist = waitlist;
		this.reservations = reservations;
		this.writer = writer;
		this.catalog = catalog;
		this.availability = availability;
		this.events = events;
		this.newTx = new TransactionTemplate(txManager);
		this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.offerDuration = Duration.ofMinutes(offerMinutes);
	}

	public Duration offerDuration() {
		return offerDuration;
	}

	// ------------------------------------------------------------------ müşteri işlemleri

	/** Dolu bir saat için sıraya girer. Saat boşsa sıraya gerek yok: doğrudan rezervasyon yapılmalı. */
	@Transactional
	public Long join(AppUserPrincipal user, Long pitchId, Instant start) {
		PitchContext ctx = catalog.publicPitch(pitchId);
		SlotView slot = slotAt(ctx, start).orElseThrow(() -> new BusinessRuleException("Bu saat bu sahada yok."));
		if (slot.state() == Slot.State.AVAILABLE) {
			throw new BusinessRuleException("Bu saat boş; sıraya girmeden rezervasyon yapabilirsiniz.");
		}
		if (slot.state() != Slot.State.TAKEN) {
			throw new BusinessRuleException("Bu saat için bekleme listesine girilemez (" + slot.state().label() + ").");
		}
		Instant now = Instant.now(clock);
		if (waitlist.activeForCustomer(user.id(), now).size() >= MAX_ACTIVE_PER_CUSTOMER) {
			throw new BusinessRuleException("Aynı anda en fazla " + MAX_ACTIVE_PER_CUSTOMER + " saat için sırada olabilirsiniz.");
		}
		try {
			return waitlist.saveAndFlush(new WaitlistEntry(ctx.business().getId(), ctx.branch().getId(), pitchId,
					user.id(), new TimeRange(slot.start(), slot.localEnd().toInstant()), now)).getId();
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu saat için zaten sıradasınız.");
		}
	}

	@Transactional
	public void leave(AppUserPrincipal user, Long entryId) {
		WaitlistEntry e = waitlist.findById(entryId)
			.filter(x -> x.getCustomerId().equals(user.id()))
			.orElseThrow(() -> new NotFoundException("Bekleme kaydı"));
		if (e.getStatus() != WaitlistEntry.Status.WAITING) {
			throw new BusinessRuleException("Teklif edilmiş saati rezervasyon sayfasından bırakabilirsiniz.");
		}
		e.leave(Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public List<EntryView> mine(AppUserPrincipal user) {
		return waitlist.activeForCustomer(user.id(), Instant.now(clock)).stream().map(e -> {
			PitchContext ctx = catalog.pitchContext(e.getPitchId());
			ZoneId zone = ctx.branch().zone();
			long position = e.getStatus() == WaitlistEntry.Status.WAITING
					? waitlist.aheadOf(e.getPitchId(), e.getStartsAt(), e.getCreatedAt(), e.getId()) + 1 : 0;
			String code = e.getOfferReservationId() == null ? null
					: reservations.findById(e.getOfferReservationId()).map(Reservation::getCode).orElse(null);
			return new EntryView(e.getId(), e.getStatus(), ctx.pitch().getName(), ctx.branch().getName(),
					e.getStartsAt().atZone(zone), e.getEndsAt().atZone(zone), position, code);
		}).toList();
	}

	/** Saha sayfasında "N kişi sırada" bilgisi için. */
	@Transactional(readOnly = true)
	public long waitingCount(Long pitchId, Instant start) {
		return waitlist.waitingCount(pitchId, start);
	}

	// ------------------------------------------------------------------ olaylar

	/** Teklif rezervasyonu onaylandıysa kayıt kabul edilmiş olur (aynı transaction). */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onConfirmed(BookingEvents.ReservationConfirmed event) {
		waitlist.findByOfferReservationIdAndStatus(event.reservationId(), WaitlistEntry.Status.OFFERED)
			.ifPresent(e -> e.accept(Instant.now(clock)));
	}

	/** Boşalan saat bir teklifse teklif kapanır (aynı transaction). */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void closeOffer(BookingEvents.SlotReleased event) {
		waitlist.findByOfferReservationIdAndStatus(event.reservationId(), WaitlistEntry.Status.OFFERED)
			.ifPresent(e -> e.expireOffer(Instant.now(clock)));
	}

	/** Boşalma kesinleştikten sonra sıradakine teklif açılır (yeni transaction'larda). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void offerAfterRelease(BookingEvents.SlotReleased event) {
		offerNext(event.pitchId(), event.start(), event.end());
	}

	/**
	 * Verilen aralıkla kesişen ve sırası olan her başlangıç saati için ilk kişiye teklif açmayı dener.
	 * Her deneme ayrı transaction'dır: saat hâlâ doluysa (EXCLUDE kısıtı) o deneme geri alınır,
	 * kimse teklif almaz ve kayıtlar sırada kalır.
	 *
	 * @return açılan teklif sayısı
	 */
	public int offerNext(Long pitchId, Instant start, Instant end) {
		int offered = 0;
		for (Instant slotStart : waitlist.waitingStartsOverlapping(pitchId, start, end)) {
			try {
				Boolean ok = newTx.execute(status -> offerFirstInQueue(pitchId, slotStart));
				if (Boolean.TRUE.equals(ok)) {
					offered++;
				}
			}
			catch (SlotUnavailableException | DataIntegrityViolationException ex) {
				log.debug("Saat hâlâ dolu veya başka teklif açık, sıradakine teklif açılmadı: {} {}", pitchId, slotStart);
			}
		}
		return offered;
	}

	private boolean offerFirstInQueue(Long pitchId, Instant slotStart) {
		Instant now = Instant.now(clock);
		List<WaitlistEntry> queue = waitlist.queueForUpdate(pitchId, slotStart);
		if (queue.isEmpty()) {
			return false;
		}
		if (!slotStart.isAfter(now)) {
			queue.forEach(e -> e.expireUnserved(now)); // saat geçti: sıradakiler kapatılır
			return false;
		}
		WaitlistEntry first = queue.getFirst();
		PitchContext ctx = catalog.pitchContext(pitchId);
		Reservation hold = Reservation.holdForCustomer(first.getBusinessId(), first.getBranchId(), pitchId,
				first.play(), ctx.pitch().getBufferMinutes(), first.getCustomerId(), offerDuration, now);
		Reservation saved = writer.persistNew(hold, ctx); // dolu ise SlotUnavailableException → geri al
		first.offer(saved.getId(), now);
		waitlist.flush();
		events.publishEvent(new BookingEvents.WaitlistOffered(first.getId(), saved.getId()));
		return true;
	}

	private Optional<SlotView> slotAt(PitchContext ctx, Instant start) {
		LocalDate localDate = start.atZone(ctx.branch().zone()).toLocalDate();
		for (LocalDate day : new LocalDate[] { localDate, localDate.minusDays(1) }) {
			DayAvailability da = availability.forDay(ctx, day);
			Optional<SlotView> match = da.slots().stream().filter(s -> s.start().equals(start)).findFirst();
			if (match.isPresent()) {
				return match;
			}
		}
		return Optional.empty();
	}

}
