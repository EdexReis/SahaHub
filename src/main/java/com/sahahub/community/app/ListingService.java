package com.sahahub.community.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sahahub.booking.app.ReservationCancelled;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.ListingApplication;
import com.sahahub.community.domain.ListingApplicationRepository;
import com.sahahub.community.domain.ListingRepository;
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamMemberRepository;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.notification.app.NotificationWriter;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Oyuncu/rakip ilanları ve başvurular.
 * <ul>
 * <li>İlanı yalnızca takımın kaptanı açar; bir kişinin aynı anda en fazla 5 açık ilanı olur.</li>
 * <li>İlan isteğe bağlı olarak kaptanın kendi onaylı, ileri tarihli rezervasyonuna bağlanır; o zaman maç
 * zamanı ve yeri rezervasyondan gelir, rezervasyon iptal edilirse ilan kapanır.</li>
 * <li>Kabul sırasında ilan satırı kilitlenir: kontenjan (rakip ilanında 1) eşzamanlı kabullerde aşılmaz.</li>
 * <li>Telefon numarası yalnızca kabul edilen başvuruda karşılıklı görünür.</li>
 * </ul>
 */
@Service
public class ListingService {

	public static final int MAX_OPEN_PER_AUTHOR = 5;
	public static final Duration DEFAULT_LIFETIME = Duration.ofDays(7);
	public static final Duration MAX_AHEAD = Duration.ofDays(60);
	private static final ZoneId TR = ZoneId.of("Europe/Istanbul");

	public record CreateCommand(Listing.Kind kind, Long teamId, Long reservationId, String city, String district,
			LocalDateTime playAt, Integer playersNeeded, Listing.Level level, String note) {
	}

	private final ListingRepository listings;
	private final ListingApplicationRepository applications;
	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final ReservationRepository reservations;
	private final CatalogService catalog;
	private final AppUserRepository users;
	private final NotificationWriter notifications;
	private final Clock clock;

	public ListingService(ListingRepository listings, ListingApplicationRepository applications, TeamRepository teams,
			TeamMemberRepository members, ReservationRepository reservations, CatalogService catalog,
			AppUserRepository users, NotificationWriter notifications, Clock clock) {
		this.listings = listings;
		this.applications = applications;
		this.teams = teams;
		this.members = members;
		this.reservations = reservations;
		this.catalog = catalog;
		this.users = users;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Transactional
	public Long create(AppUserPrincipal user, CreateCommand cmd) {
		Instant now = Instant.now(clock);
		if (cmd.kind() == null || cmd.level() == null) {
			throw new BusinessRuleException("İlan türünü ve seviyeyi seçin.");
		}
		Team team = captainTeam(user, cmd.teamId());
		if (listings.openCountByAuthor(user.id(), now) >= MAX_OPEN_PER_AUTHOR) {
			throw new BusinessRuleException("Aynı anda en fazla " + MAX_OPEN_PER_AUTHOR + " açık ilanınız olabilir.");
		}
		Integer needed = null;
		if (cmd.kind() == Listing.Kind.PLAYERS_WANTED) {
			if (cmd.playersNeeded() == null || cmd.playersNeeded() < 1 || cmd.playersNeeded() > 11) {
				throw new BusinessRuleException("Kaç oyuncu aradığınızı yazın (1-11).");
			}
			needed = cmd.playersNeeded();
		}
		String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note().strip();
		if (note != null && note.length() > 500) {
			throw new BusinessRuleException("Not en fazla 500 karakter.");
		}

		String city;
		String district;
		Instant playAt;
		Long reservationId = null;
		if (cmd.reservationId() != null) {
			Reservation r = reservations.findById(cmd.reservationId())
				.filter(x -> user.id().equals(x.getCustomerId()))
				.orElseThrow(() -> new NotFoundException("Rezervasyon"));
			if (r.getStatus() != ReservationStatus.CONFIRMED || !r.getStartsAt().isAfter(now)) {
				throw new BusinessRuleException("İlan yalnızca onaylı ve henüz oynanmamış bir rezervasyona bağlanabilir.");
			}
			PitchContext ctx = catalog.pitchContext(r.getPitchId());
			city = ctx.branch().getCity();
			district = ctx.branch().getDistrict();
			playAt = r.getStartsAt();
			reservationId = r.getId();
		}
		else {
			city = cmd.city() == null ? "" : cmd.city().strip();
			if (city.isEmpty() || city.length() > 60) {
				throw new BusinessRuleException("Şehir seçin.");
			}
			district = cmd.district() == null || cmd.district().isBlank() ? null : cmd.district().strip();
			playAt = cmd.playAt() == null ? null : cmd.playAt().atZone(TR).toInstant();
			if (playAt != null && (!playAt.isAfter(now) || playAt.isAfter(now.plus(MAX_AHEAD)))) {
				throw new BusinessRuleException("Maç zamanı gelecek 60 gün içinde olmalı.");
			}
		}
		Instant expires = playAt != null ? playAt : now.plus(DEFAULT_LIFETIME);
		Listing l = listings.save(new Listing(cmd.kind(), team.getId(), user.id(), reservationId, city, district,
				playAt, needed, cmd.level(), note, expires, now));
		return l.getId();
	}

	@Transactional
	public Long apply(AppUserPrincipal user, Long listingId, String message, Long applicantTeamId) {
		Instant now = Instant.now(clock);
		Listing l = listings.findById(listingId).orElseThrow(() -> new NotFoundException("İlan"));
		if (!l.isAcceptingAt(now)) {
			throw new BusinessRuleException("Bu ilan artık başvuru almıyor.");
		}
		if (l.getAuthorId().equals(user.id())) {
			throw new BusinessRuleException("Kendi ilanınıza başvuramazsınız.");
		}
		Long teamId = null;
		if (l.getKind() == Listing.Kind.OPPONENT_WANTED) {
			Team mine = captainTeam(user, applicantTeamId);
			if (mine.getId().equals(l.getTeamId())) {
				throw new BusinessRuleException("Takımınız kendi ilanına rakip olamaz.");
			}
			teamId = mine.getId();
		}
		else if (members.activeMembership(l.getTeamId(), user.id()).isPresent()) {
			throw new BusinessRuleException("Zaten bu takımdasınız.");
		}
		String msg = message == null || message.isBlank() ? null : message.strip();
		if (msg != null && msg.length() > 300) {
			throw new BusinessRuleException("Mesaj en fazla 300 karakter.");
		}
		ListingApplication a;
		try {
			a = applications.saveAndFlush(new ListingApplication(listingId, user.id(), teamId, msg, now));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu ilana zaten başvurdunuz.");
		}
		Team team = teams.findById(l.getTeamId()).orElseThrow();
		notify(l.getAuthorId(), "LISTING_APPLICATION", "İlanınıza başvuru var",
				user.fullName() + ", \"" + team.getName() + "\" ilanınıza (" + l.getKind().label().toLowerCase(
						java.util.Locale.forLanguageTag("tr")) + ") başvurdu.",
				"/ilanlar/" + l.getId(), "application:" + a.getId());
		return a.getId();
	}

	@Transactional
	public void withdraw(AppUserPrincipal user, Long applicationId) {
		Long listingId = applications.listingIdOf(applicationId).orElseThrow(() -> new NotFoundException("Başvuru"));
		listings.findForUpdate(listingId).orElseThrow(); // kabul ile aynı kilit sırası
		ListingApplication a = applications.findForUpdate(applicationId)
			.filter(x -> x.getApplicantId().equals(user.id()))
			.orElseThrow(() -> new NotFoundException("Başvuru"));
		if (a.getStatus() != ListingApplication.Status.PENDING) {
			throw new BusinessRuleException("Yalnızca yanıt bekleyen başvuru geri çekilebilir.");
		}
		a.withdraw(Instant.now(clock));
	}

	/**
	 * Başvuruyu kabul eder. İlan satırı kilitlenir; kontenjan dolarsa ilan FILLED olur ve bekleyen diğer
	 * başvurular "kabul edilmedi" yapılır (başvuranlara bildirim gider).
	 */
	@Transactional
	public void accept(AppUserPrincipal user, Long applicationId) {
		Instant now = Instant.now(clock);
		Long listingId = applications.listingIdOf(applicationId).orElseThrow(() -> new NotFoundException("Başvuru"));
		// Kilit sırası hep aynı: önce ilan, sonra başvuru (aynı başvuru iki kez kabul edilemez)
		Listing l = listings.findForUpdate(listingId)
			.filter(x -> x.getAuthorId().equals(user.id()))
			.orElseThrow(() -> new NotFoundException("Başvuru"));
		ListingApplication a = applications.findForUpdate(applicationId).orElseThrow();
		if (!l.isAcceptingAt(now)) {
			throw new BusinessRuleException("İlan kapalı veya süresi dolmuş; başvuru kabul edilemez.");
		}
		if (a.getStatus() != ListingApplication.Status.PENDING) {
			throw new BusinessRuleException("Bu başvuru zaten yanıtlanmış.");
		}
		a.accept(now);
		applications.flush();
		l.onAccepted(applications.acceptedCount(l.getId()), now);
		Team team = teams.findById(l.getTeamId()).orElseThrow();
		notify(a.getApplicantId(), "LISTING_ACCEPTED", "Başvurunuz kabul edildi",
				"\"" + team.getName() + "\" başvurunuzu kabul etti. İletişim bilgisi ilan sayfasında.",
				"/ilanlar/" + l.getId(), "application-accepted:" + a.getId());
		if (l.getStatus() == Listing.Status.FILLED) {
			for (ListingApplication other : applications.pending(l.getId())) {
				other.reject(now);
				notify(other.getApplicantId(), "LISTING_REJECTED", "İlan doldu",
						"\"" + team.getName() + "\" ilanı doldu; başvurunuz kabul edilmedi.", "/ilanlar/" + l.getId(),
						"application-rejected:" + other.getId());
			}
		}
	}

	@Transactional
	public void reject(AppUserPrincipal user, Long applicationId) {
		Long listingId = applications.listingIdOf(applicationId).orElseThrow(() -> new NotFoundException("Başvuru"));
		Listing l = listings.findForUpdate(listingId)
			.filter(x -> x.getAuthorId().equals(user.id()))
			.orElseThrow(() -> new NotFoundException("Başvuru"));
		ListingApplication a = applications.findForUpdate(applicationId).orElseThrow();
		if (a.getStatus() != ListingApplication.Status.PENDING) {
			throw new BusinessRuleException("Bu başvuru zaten yanıtlanmış.");
		}
		a.reject(Instant.now(clock));
		Team team = teams.findById(l.getTeamId()).orElseThrow();
		notify(a.getApplicantId(), "LISTING_REJECTED", "Başvurunuz kabul edilmedi",
				"\"" + team.getName() + "\" bu kez başvurunuzu kabul etmedi.", "/ilanlar/" + l.getId(),
				"application-rejected:" + a.getId());
	}

	@Transactional
	public void close(AppUserPrincipal user, Long listingId) {
		Listing l = listings.findForUpdate(listingId)
			.filter(x -> x.getAuthorId().equals(user.id()))
			.orElseThrow(() -> new NotFoundException("İlan"));
		if (l.getStatus() != Listing.Status.OPEN) {
			throw new BusinessRuleException("İlan zaten kapalı.");
		}
		l.close(Instant.now(clock));
	}

	/** Bağlı rezervasyon iptal edilirse ilan da kapanır (aynı transaction'da). */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onReservationCancelled(ReservationCancelled event) {
		Instant now = Instant.now(clock);
		listings.openForReservation(event.reservationId()).forEach(l -> l.close(now));
	}

	// ------------------------------------------------------------------ yardımcılar

	private Team captainTeam(AppUserPrincipal user, Long teamId) {
		if (teamId == null) {
			throw new BusinessRuleException("Takım seçin. İlanı yalnızca takım kaptanı açabilir.");
		}
		Team t = teams.findById(teamId).filter(Team::isActive).orElseThrow(() -> new NotFoundException("Takım"));
		boolean captain = members.activeMembership(teamId, user.id()).map(m -> m.isCaptain()).orElse(false);
		if (!captain) {
			throw new BusinessRuleException("Bu işlemi yalnızca takımın kaptanı yapabilir.");
		}
		return t;
	}

	private void notify(Long userId, String kind, String title, String body, String link, String dedupKey) {
		AppUser u = users.findById(userId).orElseThrow();
		notifications.write(new NotificationWriter.Recipient(u.getId(), u.getEmail(), u.getPhone(), u.isNotifyEmail(),
				u.isNotifySms()), kind, title, body, link, dedupKey);
	}

}
