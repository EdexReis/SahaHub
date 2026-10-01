package com.sahahub.notification.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sahahub.booking.app.BookingEvents;
import com.sahahub.booking.app.PaymentStatusPort;
import com.sahahub.booking.app.ReservationCancelled;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationSeriesRepository;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.notification.app.NotificationWriter.Recipient;

/**
 * Rezervasyon olaylarından bildirim üretir.
 * <p>
 * Olay dinleyicileri BEFORE_COMMIT çalışır: bildirim satırları rezervasyon değişikliğiyle aynı
 * transaction'da yazılır. Hatırlatmalar zamanlanmış görevle üretilir ve dedup_key sayesinde her
 * rezervasyon için yalnızca bir kez oluşur.
 */
@Component
public class ReservationNotifications {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM EEEE HH:mm",
			Locale.forLanguageTag("tr"));
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

	private final ReservationRepository reservations;
	private final ReservationSeriesRepository series;
	private final AppUserRepository users;
	private final CatalogService catalog;
	private final PaymentStatusPort paymentStatus;
	private final NotificationWriter writer;
	private final Clock clock;

	public ReservationNotifications(ReservationRepository reservations, ReservationSeriesRepository series,
			AppUserRepository users, CatalogService catalog, PaymentStatusPort paymentStatus, NotificationWriter writer,
			Clock clock) {
		this.reservations = reservations;
		this.series = series;
		this.users = users;
		this.catalog = catalog;
		this.paymentStatus = paymentStatus;
		this.writer = writer;
		this.clock = clock;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onConfirmed(BookingEvents.ReservationConfirmed e) {
		Reservation r = reservations.findById(e.reservationId()).orElseThrow();
		if (r.getSeriesId() != null) {
			return; // seri için tek bildirim (onSeriesCreated)
		}
		notify(r, "RESERVATION_CONFIRMED", "Rezervasyonunuz onaylandı", describe(r), "confirmed:" + r.getId());
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onCancelled(ReservationCancelled e) {
		Reservation r = reservations.findById(e.reservationId()).orElseThrow();
		String who = e.byCustomer() ? "İsteğiniz üzerine iptal edildi." : "Şube tarafından iptal edildi.";
		notify(r, "RESERVATION_CANCELLED", "Rezervasyonunuz iptal edildi", describe(r) + ". " + who,
				"cancelled:" + r.getId());
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onSeriesCreated(BookingEvents.SeriesCreated e) {
		var s = series.findById(e.seriesId()).orElseThrow();
		List<Reservation> list = reservations.findBySeriesIdOrderBySeriesIndex(e.seriesId());
		if (list.isEmpty()) {
			return;
		}
		Reservation first = list.getFirst();
		PitchContext ctx = catalog.pitchContext(first.getPitchId());
		String body = ctx.pitch().getName() + " · " + ctx.branch().getName() + " · her hafta "
				+ first.getStartsAt().atZone(ctx.branch().zone()).format(DateTimeFormatter.ofPattern("EEEE HH:mm",
						Locale.forLanguageTag("tr")))
				+ " · " + e.count() + " maç (ilk: " + first.getStartsAt().atZone(ctx.branch().zone()).format(WHEN) + ")";
		notify(first, "SERIES_CREATED", "Düzenli rezervasyonunuz oluşturuldu", body, "series:" + s.getId());
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onWaitlistOffer(BookingEvents.WaitlistOffered e) {
		Reservation r = reservations.findById(e.reservationId()).orElseThrow();
		ZoneId zone = catalog.pitchContext(r.getPitchId()).branch().zone();
		notify(r, "WAITLIST_OFFER", "Beklediğiniz saat boşaldı",
				describe(r) + ". Saat " + r.getHoldExpiresAt().atZone(zone).format(HM)
						+ " saatine kadar sizin için tutuluyor; onaylamazsanız sıradakine geçer.",
				"offer:" + e.entryId());
	}

	/**
	 * Maç hatırlatması (24 saat içinde başlayan onaylı maçlar) ve kapora hatırlatması (48 saat içinde
	 * başlayıp kaporası ödenmemiş maçlar). Görev sık çalışsa da dedup_key her rezervasyona bir hatırlatma
	 * düşmesini sağlar.
	 *
	 * @return yeni oluşturulan hatırlatma denemesi sayısı (tekrar olanlar veritabanında yok sayılır)
	 */
	@Transactional
	public int enqueueReminders() {
		Instant now = Instant.now(clock);
		int count = 0;
		for (Reservation r : reservations.confirmedStartingBetween(now, now.plus(Duration.ofHours(24)))) {
			notify(r, "MATCH_REMINDER", "Maç hatırlatması", describe(r), "reminder:" + r.getId());
			count++;
		}
		List<Reservation> soon = reservations.confirmedStartingBetween(now, now.plus(Duration.ofHours(48)));
		Map<Long, PaymentStatusPort.Badge> badges = paymentStatus.badges(soon);
		for (Reservation r : soon) {
			PaymentStatusPort.Badge b = badges.get(r.getId());
			if (b != null && "DEPOSIT_DUE".equals(b.state())) {
				notify(r, "PAYMENT_REMINDER", "Kapora bekleniyor",
						describe(r) + ". Kapora ödenmezse rezervasyon şube tarafından iptal edilebilir.",
						"deposit-reminder:" + r.getId());
				count++;
			}
		}
		return count;
	}

	// ------------------------------------------------------------------ yardımcılar

	private void notify(Reservation r, String kind, String title, String body, String dedupKey) {
		writer.write(recipientOf(r), kind, title, body, "/rezervasyon/" + r.getCode(), dedupKey);
	}

	/** Kayıtlı müşteri: tercihlerine göre; misafir: yalnızca telefonla kısa mesaj (demo kanal). */
	private Recipient recipientOf(Reservation r) {
		if (r.getCustomerId() != null) {
			AppUser u = users.findById(r.getCustomerId()).orElseThrow();
			return new Recipient(u.getId(), u.getEmail(), u.getPhone(), u.isNotifyEmail(), u.isNotifySms());
		}
		return new Recipient(null, null, r.getGuestPhone(), false, r.getGuestPhone() != null);
	}

	private String describe(Reservation r) {
		PitchContext ctx = catalog.pitchContext(r.getPitchId());
		ZoneId zone = ctx.branch().zone();
		return ctx.pitch().getName() + " · " + ctx.branch().getName() + " · "
				+ r.getStartsAt().atZone(zone).format(WHEN) + "–" + r.getEndsAt().atZone(zone).format(HM) + " · kod "
				+ r.getCode();
	}

}
