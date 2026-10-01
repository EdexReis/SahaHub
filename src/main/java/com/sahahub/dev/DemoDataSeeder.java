package com.sahahub.dev;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.sahahub.booking.app.OccupancyService;
import com.sahahub.booking.domain.Channel;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.Reservation;
import com.sahahub.booking.domain.ReservationPriceLine;
import com.sahahub.booking.domain.ReservationPriceLineRepository;
import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationSeries;
import com.sahahub.booking.domain.ReservationSeriesRepository;
import com.sahahub.booking.domain.WaitlistEntry;
import com.sahahub.booking.domain.WaitlistRepository;
import com.sahahub.business.domain.Branch;
import com.sahahub.business.domain.BranchOpeningHours;
import com.sahahub.business.domain.BranchOpeningHoursRepository;
import com.sahahub.business.domain.BranchRepository;
import com.sahahub.business.domain.BranchSpecialDay;
import com.sahahub.business.domain.BranchSpecialDayRepository;
import com.sahahub.business.domain.Business;
import com.sahahub.business.domain.BusinessRepository;
import com.sahahub.business.domain.DayHours;
import com.sahahub.business.domain.Pitch;
import com.sahahub.business.domain.PitchBlock;
import com.sahahub.business.domain.PitchBlockRepository;
import com.sahahub.business.domain.PitchRepository;
import com.sahahub.business.domain.Surface;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.domain.StaffMembership;
import com.sahahub.identity.domain.StaffMembershipRepository;
import com.sahahub.identity.domain.StaffRole;
import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.ListingApplication;
import com.sahahub.community.domain.ListingApplicationRepository;
import com.sahahub.community.domain.ListingRepository;
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamMember;
import com.sahahub.community.domain.TeamMemberRepository;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.notification.app.NotificationWriter;
import com.sahahub.tournament.domain.RoundRobin;
import com.sahahub.tournament.domain.Tournament;
import com.sahahub.tournament.domain.TournamentEntry;
import com.sahahub.tournament.domain.TournamentEntryRepository;
import com.sahahub.tournament.domain.TournamentMatch;
import com.sahahub.tournament.domain.TournamentMatchRepository;
import com.sahahub.tournament.domain.TournamentRepository;
import com.sahahub.pricing.domain.PriceCalculator;
import com.sahahub.pricing.domain.PriceQuote;
import com.sahahub.pricing.domain.PriceRule;
import com.sahahub.pricing.domain.PriceRuleRepository;
import com.sahahub.payment.domain.CashSession;
import com.sahahub.payment.domain.CashSessionRepository;
import com.sahahub.payment.domain.Expense;
import com.sahahub.payment.domain.ExpenseRepository;
import com.sahahub.payment.domain.Payment;
import com.sahahub.payment.domain.PaymentRepository;
import com.sahahub.payment.provider.SimulatedPaymentProvider;
import com.sahahub.pricing.domain.Coupon;
import com.sahahub.pricing.domain.CouponRepository;
import com.sahahub.pricing.domain.DepositPolicy;
import com.sahahub.pricing.domain.ExtraService;
import com.sahahub.pricing.domain.ExtraServiceRepository;
import com.sahahub.shared.domain.TimeRange;

/**
 * YALNIZCA geliştirme profilinde (dev) ve veritabanı boşken çalışır. Tüm isimler, telefonlar
 * ve adresler kurgusaldır. Demo hesap parolası README'de yazılıdır; canlı ortamda bu sınıf
 * hiç yüklenmez (@Profile("dev")).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "sahahub.demo-data", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

	static final String DEMO_PASSWORD = "SahaHub.demo1";
	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
	private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
	private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
	private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

	private final TransactionTemplate tx;
	private final PasswordEncoder encoder;
	private final Clock clock;
	private final AppUserRepository users;
	private final StaffMembershipRepository memberships;
	private final BusinessRepository businesses;
	private final BranchRepository branches;
	private final BranchOpeningHoursRepository hours;
	private final BranchSpecialDayRepository specialDays;
	private final PitchRepository pitches;
	private final PitchBlockRepository blocks;
	private final PriceRuleRepository priceRules;
	private final ReservationRepository reservations;
	private final ReservationPriceLineRepository priceLines;
	private final OccupancyService occupancy;
	private final ExtraServiceRepository extras;
	private final CouponRepository coupons;
	private final PaymentRepository payments;
	private final CashSessionRepository cashSessions;
	private final ExpenseRepository expenses;
	private final SimulatedPaymentProvider simProvider;
	private final JdbcTemplate jdbc;
	private final ReservationSeriesRepository seriesRepo;
	private final WaitlistRepository waitlist;
	private final NotificationWriter notifications;
	private final TeamRepository teams;
	private final TeamMemberRepository teamMembers;
	private final ListingRepository listings;
	private final ListingApplicationRepository applications;
	private final TournamentRepository tournaments;
	private final TournamentEntryRepository entries;
	private final TournamentMatchRepository matches;

	DemoDataSeeder(TransactionTemplate tx, PasswordEncoder encoder, Clock clock, AppUserRepository users,
			StaffMembershipRepository memberships, BusinessRepository businesses, BranchRepository branches,
			BranchOpeningHoursRepository hours, BranchSpecialDayRepository specialDays, PitchRepository pitches,
			PitchBlockRepository blocks, PriceRuleRepository priceRules, ReservationRepository reservations,
			ReservationPriceLineRepository priceLines, OccupancyService occupancy, ExtraServiceRepository extras,
			CouponRepository coupons, PaymentRepository payments, CashSessionRepository cashSessions,
			ExpenseRepository expenses, SimulatedPaymentProvider simProvider, JdbcTemplate jdbc,
			ReservationSeriesRepository seriesRepo, WaitlistRepository waitlist, NotificationWriter notifications,
			TeamRepository teams, TeamMemberRepository teamMembers, ListingRepository listings,
			ListingApplicationRepository applications, TournamentRepository tournaments,
			TournamentEntryRepository entries, TournamentMatchRepository matches) {
		this.tx = tx;
		this.encoder = encoder;
		this.clock = clock;
		this.users = users;
		this.memberships = memberships;
		this.businesses = businesses;
		this.branches = branches;
		this.hours = hours;
		this.specialDays = specialDays;
		this.pitches = pitches;
		this.blocks = blocks;
		this.priceRules = priceRules;
		this.reservations = reservations;
		this.priceLines = priceLines;
		this.occupancy = occupancy;
		this.extras = extras;
		this.coupons = coupons;
		this.payments = payments;
		this.cashSessions = cashSessions;
		this.expenses = expenses;
		this.simProvider = simProvider;
		this.jdbc = jdbc;
		this.seriesRepo = seriesRepo;
		this.waitlist = waitlist;
		this.notifications = notifications;
		this.teams = teams;
		this.teamMembers = teamMembers;
		this.listings = listings;
		this.applications = applications;
		this.tournaments = tournaments;
		this.entries = entries;
		this.matches = matches;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (businesses.count() > 0) {
			return;
		}
		tx.executeWithoutResult(status -> seed());
		log.info("Demo veri oluşturuldu (yalnızca dev profili). Demo hesaplar README.md içinde.");
	}

	private void seed() {
		Instant now = Instant.now(clock);
		LocalDate today = LocalDate.now(clock.withZone(IST));

		// ------------------------------------------------ kullanıcılar
		AppUser admin = user("admin@sahahub.test", "Platform Yöneticisi", null, now);
		admin.grantPlatformAdmin();
		AppUser owner1 = user("sahip@yesilvadi.test", "Selin Aksoy", "0532 000 10 01", now);
		AppUser manager1 = user("mudur.kadikoy@yesilvadi.test", "Burak Demirtaş", "0532 000 10 02", now);
		AppUser reception1 = user("resepsiyon.kadikoy@yesilvadi.test", "Elif Kaya", "0532 000 10 03", now);
		AppUser owner2 = user("sahip@kuzeyhali.test", "Murat Çelik", "0532 000 20 01", now);
		AppUser customer = user("musteri@sahahub.test", "Deniz Arslan", "0555 000 30 01", now);
		AppUser captain = user("kaptan@sahahub.test", "Emre Yıldız", "0555 000 30 02", now);
		AppUser longName = user("uzun.isim@sahahub.test", "Muhammed Abdurrahman Karaosmanoğlu-Yıldırımhanlı",
				"0555 000 30 03", now);

		// ------------------------------------------------ işletme 1: iki şube
		Business yesil = businesses.save(new Business("Yeşilvadi Spor Tesisleri",
				"İstanbul Anadolu yakasında iki şubeli halı saha işletmesi.", "0216 000 00 00",
				"iletisim@yesilvadi.test", now));
		Branch kadikoy = branch(yesil, "Kadıköy Şubesi", "Moda Cad. No: 00 (kurgusal)", "Kadıköy", "İstanbul",
				"0216 000 00 01", now);
		Branch atasehir = branch(yesil, "Ataşehir Şubesi", "Barbaros Mah. Örnek Sok. No: 0", "Ataşehir", "İstanbul",
				"0216 000 00 02", now);
		weekly(kadikoy, LocalTime.of(9, 0), LocalTime.of(1, 0), LocalTime.of(8, 0), LocalTime.of(2, 0));
		weekly(atasehir, LocalTime.of(10, 0), LocalTime.of(0, 0), LocalTime.of(9, 0), LocalTime.of(0, 0));
		specialDays.save(new BranchSpecialDay(atasehir.getId(), today.plusDays(9), DayHours.CLOSED,
				"Zemin yenileme (kurgusal tatil günü)"));

		// Aşama 3: kapora kuralları (IBAN'lar kurgusaldır)
		kadikoy.changeDepositPolicy(new DepositPolicy(DepositPolicy.Type.PERCENT, new BigDecimal("30")));
		kadikoy.changeBankIban("TR00 0000 0000 0000 0000 0000 01");
		atasehir.changeDepositPolicy(new DepositPolicy(DepositPolicy.Type.FIXED, new BigDecimal("300")));
		atasehir.changeBankIban("TR00 0000 0000 0000 0000 0000 02");
		for (Branch b : List.of(kadikoy, atasehir)) {
			extras.save(new ExtraService(b.getId(), "Krampon kiralama", new BigDecimal("60"), now));
			extras.save(new ExtraService(b.getId(), "Hakem", new BigDecimal("450"), now));
			extras.save(new ExtraService(b.getId(), "İçecek paketi (14 kişilik)", new BigDecimal("350"), now));
		}
		coupons.save(new Coupon(yesil.getId(), "HOSGELDIN", Coupon.Kind.PERCENT, new BigDecimal("10"), 100, null, null,
				now));
		coupons.save(new Coupon(yesil.getId(), "ILKMAC", Coupon.Kind.FIXED, new BigDecimal("200"), 1, null, null, now));

		memberships.save(StaffMembership.owner(owner1.getId(), yesil.getId(), now));
		memberships.save(StaffMembership.forBranch(manager1.getId(), yesil.getId(), kadikoy.getId(),
				StaffRole.BRANCH_MANAGER, now));
		memberships.save(StaffMembership.forBranch(reception1.getId(), yesil.getId(), kadikoy.getId(),
				StaffRole.RECEPTION, now));

		Pitch k1 = pitch(kadikoy, "Saha 1 · Kapalı", 14, Surface.ARTIFICIAL_TURF, "1400", true, 60, 60, 0, now);
		Pitch k2 = pitch(kadikoy, "Saha 2 · Açık", 14, Surface.ARTIFICIAL_TURF, "1200", false, 60, 60, 0, now);
		Pitch k3 = pitch(kadikoy, "Mini Saha", 10, Surface.ARTIFICIAL_TURF, "900", false, 60, 75, 15, now);
		Pitch a1 = pitch(atasehir, "Çatı Sahası", 12, Surface.ARTIFICIAL_TURF, "1300", false, 60, 60, 0, now);
		Pitch a2 = pitch(atasehir, "Spor Salonu (Parke)", 10, Surface.PARQUET, "1500", true, 60, 60, 0, now);

		for (Pitch p : List.of(k1, k2, a1, a2)) {
			BigDecimal base = p.getBaseHourlyPrice();
			priceRules.save(new PriceRule(p.getId(), "Hafta içi akşam", WEEKDAYS, LocalTime.of(18, 0), null,
					base.add(new BigDecimal("400")), 10));
			priceRules.save(new PriceRule(p.getId(), "Hafta sonu", WEEKEND, LocalTime.of(0, 0), null,
					base.add(new BigDecimal("500")), 5));
			priceRules.save(new PriceRule(p.getId(), "Gece indirimi", EnumSet.allOf(DayOfWeek.class),
					LocalTime.of(0, 0), LocalTime.of(2, 0), base.subtract(new BigDecimal("100")), 20));
		}
		priceRules.save(new PriceRule(k3.getId(), "Akşam", EnumSet.allOf(DayOfWeek.class), LocalTime.of(18, 0), null,
				new BigDecimal("1150"), 10));

		// ------------------------------------------------ işletme 2: tek şube
		Business kuzey = businesses.save(new Business("Kuzey Halı Saha", "Ankara Çankaya'da iki sahalı tesis.",
				"0312 000 00 00", "iletisim@kuzeyhali.test", now));
		Branch cankaya = branch(kuzey, "Çankaya", "Örnek Bulvarı No: 00", "Çankaya", "Ankara", "0312 000 00 01", now);
		weekly(cankaya, LocalTime.of(10, 0), LocalTime.of(0, 0), LocalTime.of(10, 0), LocalTime.of(0, 0));
		memberships.save(StaffMembership.owner(owner2.getId(), kuzey.getId(), now));
		extras.save(new ExtraService(cankaya.getId(), "Hakem", new BigDecimal("400"), now));
		coupons.save(new Coupon(kuzey.getId(), "KUZEY50", Coupon.Kind.FIXED, new BigDecimal("50"), 20, null, null, now));
		Pitch c1 = pitch(cankaya, "A Sahası", 14, Surface.ARTIFICIAL_TURF, "1100", false, 60, 60, 0, now);
		Pitch c2 = pitch(cankaya, "B Sahası", 12, Surface.ARTIFICIAL_TURF, "1000", true, 90, 90, 0, now);
		priceRules.save(new PriceRule(c1.getId(), "Akşam", EnumSet.allOf(DayOfWeek.class), LocalTime.of(19, 0), null,
				new BigDecimal("1450"), 10));

		// ------------------------------------------------ rezervasyonlar (farklı günler/durumlar)
		// Bugün Kadıköy'ün akşamı yoğun: takvimin dolu görünümü bununla denenir.
		int[] eveningHours = { 18, 19, 20, 21, 22, 23 };
		String[] guests = { "Ali Vural", "Kaan Er", "Okan Tunç", "Mert Aydın", "Barış Uçar", "Can Ekinci" };
		List<Reservation> evening = new java.util.ArrayList<>();
		for (int i = 0; i < eveningHours.length; i++) {
			evening.add(staffBooked(kadikoy, k1, today, eveningHours[i], 60, null, guests[i], Channel.PHONE, reception1,
					now));
		}
		Reservation denizToday = customerBooked(kadikoy, k2, today, 20, customer, now);
		customerBooked(kadikoy, k2, today, 21, longName, now);
		staffBooked(kadikoy, k2, today, 22, 90, null, "Şirket Turnuvası (Kurgusal A.Ş.)", Channel.WALK_IN,
				manager1, now);
		staffBooked(kadikoy, k3, today, 19, 60, captain, null, Channel.PHONE, reception1, now);
		staffBooked(kadikoy, k1, today.plusDays(1), 0, 60, null, "Gece Ligi", Channel.PHONE, reception1, now);
		customerHeld(kadikoy, k2, today.plusDays(1), 21, captain, now);
		Reservation denizLater = customerBooked(kadikoy, k1, today.plusDays(2), 21, customer, now);
		customerBooked(atasehir, a1, today.plusDays(3), 20, captain, now);
		customerBooked(cankaya, c1, today.plusDays(1), 20, customer, now);
		staffBooked(cankaya, c2, today.plusDays(1), 19, 90, null, "Çankaya Kartalları", Channel.PHONE, owner2, now);

		// Geçmiş kayıtlar: tamamlanan, gelmeyen, iptal edilen
		Reservation done = staffBooked(kadikoy, k1, today.minusDays(1), 20, 60, customer, null, Channel.PHONE,
				reception1, now.minus(Duration.ofDays(3)));
		done.complete(now);
		Reservation noShow = staffBooked(kadikoy, k2, today.minusDays(1), 21, 60, null, "Volkan Er", Channel.PHONE,
				reception1, now.minus(Duration.ofDays(3)));
		noShow.markNoShow(now);
		Reservation cancelled = customerBooked(kadikoy, k1, today.plusDays(4), 20, customer, now);
		cancelled.cancel(customer.getId(), "Müşteri iptali", now);
		occupancy.release(PitchOccupancy.Source.RESERVATION, cancelled.getId());

		// Ödemeler ve kasa (Aşama 3). Dünkü kasa kapanmış (50 ₺ açık), bugünkü kasa açık.
		Instant yesterdayOpen = today.minusDays(1).atTime(9, 0).atZone(IST).toInstant();
		CashSession yesterday = cashSessions.save(new CashSession(kadikoy.getId(), new BigDecimal("500"),
				reception1.getId(), yesterdayOpen));
		cash(done, done.getTotalAmount(), yesterday, reception1, yesterdayOpen.plus(Duration.ofHours(12)));
		pos(noShow, noShow.depositPolicy().depositFor(noShow.getTotalAmount()), reception1,
				yesterdayOpen.plus(Duration.ofHours(10)));
		expenses.save(new Expense(yesil.getId(), kadikoy.getId(), Expense.Category.UTILITIES, new BigDecimal("1200"),
				"Elektrik faturası (kurgusal)", null, manager1.getId(), yesterdayOpen.plus(Duration.ofHours(2))));
		BigDecimal expected = new BigDecimal("500").add(done.getTotalAmount());
		yesterday.close(expected, expected.subtract(new BigDecimal("50")), "Bozuk para eksiği (kurgusal)",
				reception1.getId(), yesterdayOpen.plus(Duration.ofHours(15)));
		cashSessions.saveAndFlush(yesterday); // kapanış yazılmadan yeni kasa açılamaz (tek açık kasa kısıtı)

		Instant todayOpen = today.atTime(9, 0).atZone(IST).toInstant();
		CashSession todaySession = cashSessions.save(new CashSession(kadikoy.getId(), new BigDecimal("500"),
				reception1.getId(), todayOpen.isAfter(now) ? now : todayOpen));
		cash(evening.get(0), evening.get(0).depositPolicy().depositFor(evening.get(0).getTotalAmount()), todaySession,
				reception1, now);
		pos(evening.get(1), evening.get(1).getTotalAmount(), reception1, now);
		expenses.save(new Expense(yesil.getId(), kadikoy.getId(), Expense.Category.SUPPLIES, new BigDecimal("250"),
				"Çim fırçası (kurgusal)", todaySession.getId(), manager1.getId(), now));
		onlinePaid(denizToday, denizToday.depositPolicy().depositFor(denizToday.getTotalAmount()), now);
		payments.save(Payment.pendingCharge(denizLater.getBusinessId(), denizLater.getBranchId(), denizLater.getId(),
				Payment.Method.BANK_TRANSFER, new BigDecimal("500"), "TRY", "demo-transfer-" + denizLater.getId(),
				"Deniz Arslan", null, customer.getId(), now));

		// Bakım kapatmaları
		block(k3, today, 14, 17, PitchBlock.Reason.MAINTENANCE, "Çim fırçalama", manager1, now);
		block(a2, today.plusDays(2), 10, 13, PitchBlock.Reason.EVENT, "Okul etkinliği", owner1, now);

		// ------------------------------------------------ Aşama 4: düzenli rezervasyon, bekleme listesi, bildirimler
		// Emre'nin takımı 6 hafta boyunca her hafta aynı gün 19:00'da Saha 2'de oynar.
		LocalDate seriesStart = today.plusDays(3);
		ReservationSeries series = seriesRepo.save(new ReservationSeries(yesil.getId(), kadikoy.getId(), k2.getId(),
				captain.getId(), null, null, seriesStart, LocalTime.of(19, 0), 60, 6, Channel.PHONE,
				"Emre'nin takımı (kurgusal)", reception1.getId(), now));
		for (int i = 0; i < 6; i++) {
			TimeRange play = play(seriesStart.plusWeeks(i), 19, 60);
			Reservation r = Reservation.confirmedByStaff(yesil.getId(), kadikoy.getId(), k2.getId(), play,
					k2.getBufferMinutes(), Channel.PHONE, captain.getId(), null, null, null, reception1.getId(), now);
			r.attachToSeries(series.getId(), i + 1);
			persist(r, k2);
		}

		// Bugün 21:00 Saha 1 dolu; Deniz ve Emre sırada (Deniz önce)
		TimeRange wanted = play(today, 21, 60);
		waitlist.save(new WaitlistEntry(yesil.getId(), kadikoy.getId(), k1.getId(), customer.getId(), wanted,
				now.minus(Duration.ofMinutes(30))));
		waitlist.save(new WaitlistEntry(yesil.getId(), kadikoy.getId(), k1.getId(), captain.getId(), wanted,
				now.minus(Duration.ofMinutes(10))));

		// Emre SMS bildirimi de istiyor (demo kanal: gönderilmez, yönetici demo kutusunda görünür)
		captain.changeNotificationPreferences(true, true, captain.getPhone());
		notifications.write(new NotificationWriter.Recipient(customer.getId(), customer.getEmail(), customer.getPhone(),
				customer.isNotifyEmail(), customer.isNotifySms()), "RESERVATION_CONFIRMED", "Rezervasyonunuz onaylandı",
				"Saha 1 · Kapalı · Kadıköy Şubesi · kod " + denizLater.getCode(), "/rezervasyon/" + denizLater.getCode(),
				"demo:confirmed:" + denizLater.getId());
		notifications.write(new NotificationWriter.Recipient(captain.getId(), captain.getEmail(), captain.getPhone(),
				true, true), "SERIES_CREATED", "Düzenli rezervasyonunuz oluşturuldu",
				"Saha 2 · Açık · Kadıköy Şubesi · her hafta 19:00 · 6 maç", null, "demo:series:" + series.getId());
		Reservation firstOfSeries = reservations.findBySeriesIdOrderBySeriesIndex(series.getId()).getFirst();
		seedCommunity(captain, customer, longName, firstOfSeries, today, now);
		seedLeague(yesil, kadikoy, k3, manager1, today, now);

		notifications.write(new NotificationWriter.Recipient(null, null, "0555 000 99 99", false, true),
				"RESERVATION_CONFIRMED", "Rezervasyonunuz onaylandı",
				"Saha 1 · Kapalı · Kadıköy Şubesi · bugün 20:00 · kod " + evening.get(2).getCode(), null,
				"demo:guest:" + evening.get(2).getId());
	}

	/** Aşama 5: iki takım, bir rakip ilanı (rezervasyona bağlı) ve bir oyuncu ilanı (bekleyen başvurulu). */
	private void seedCommunity(AppUser captain, AppUser customer, AppUser longName, Reservation captainsMatch,
			LocalDate today, Instant now) {
		Team eagles = teams.save(new Team("Kadıköy Kartalları", "İstanbul", "KARTAL2026", now));
		teamMembers.save(new TeamMember(eagles.getId(), captain.getId(), TeamMember.Role.CAPTAIN, now));
		teamMembers.save(new TeamMember(eagles.getId(), customer.getId(), TeamMember.Role.MEMBER, now));
		teamMembers.save(new TeamMember(eagles.getId(), longName.getId(), TeamMember.Role.MEMBER, now));
		Team bolts = teams.save(new Team("Moda Şimşekleri", "İstanbul", "SIMSEK2026", now));
		teamMembers.save(new TeamMember(bolts.getId(), customer.getId(), TeamMember.Role.CAPTAIN, now));

		listings.save(new Listing(Listing.Kind.OPPONENT_WANTED, eagles.getId(), captain.getId(), captainsMatch.getId(),
				"İstanbul", "Kadıköy", captainsMatch.getStartsAt(), null, Listing.Level.INTERMEDIATE,
				"Saha ücreti yarı yarıya. Formalarımız kırmızı.", captainsMatch.getStartsAt(), now));
		Instant playAt = today.plusDays(5).atTime(20, 0).atZone(IST).toInstant();
		Listing need = listings.save(new Listing(Listing.Kind.PLAYERS_WANTED, bolts.getId(), customer.getId(), null,
				"İstanbul", "Kadıköy", playAt, 2, Listing.Level.CASUAL,
				"Bir kaleci ve bir defans oyuncusu arıyoruz.", playAt, now));
		applications.save(new ListingApplication(need.getId(), longName.getId(), null, "Defansta oynayabilirim.", now));
	}

	/**
	 * Aşama 5: Kadıköy'de 6 takımlı tek devre lig. İlk hafta geçen hafta oynandı, ikinci hafta Mini Saha'da
	 * planlandı (takvimde görünür), kalan haftalar planlanmadı.
	 */
	private void seedLeague(Business business, Branch branch, Pitch pitch, AppUser manager, LocalDate today,
			Instant now) {
		Tournament t = tournaments.save(new Tournament(business.getId(), branch.getId(), "Kadıköy Kış Ligi 2026", false,
				3, 1, 0, manager.getId(), now.minus(Duration.ofDays(14))));
		List<Long> ids = new java.util.ArrayList<>();
		for (String n : List.of("Kadıköy Kartalları", "Moda Şimşekleri", "Fenerbahçe Mah. SK", "Göztepe Gençlik",
				"Acıbadem Yıldızları", "Kurgusal FK")) {
			ids.add(entries.save(new TournamentEntry(t.getId(), n, now.minus(Duration.ofDays(14)))).getId());
		}
		int[][] scores = { { 3, 1 }, { 2, 2 }, { 0, 1 } };
		int i = 0;
		int j = 0;
		for (RoundRobin.Pairing p : RoundRobin.generate(ids, false)) {
			TournamentMatch m = matches.save(new TournamentMatch(t.getId(), p.round(), p.home(), p.away()));
			if (p.round() == 1) {
				TimeRange play = play(today.minusDays(7), 20 + i, 60);
				m.schedule(pitch.getId(), play);
				occupancy.occupy(pitch.getId(), play, PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
				m.recordResult(scores[i][0], scores[i][1], now);
				i++;
			}
			else if (p.round() == 2) {
				TimeRange play = play(today.plusDays(6), 20 + j, 60);
				m.schedule(pitch.getId(), play);
				occupancy.occupy(pitch.getId(), play, PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
				j++;
			}
		}
		t.start(now.minus(Duration.ofDays(10)));
	}

	// ------------------------------------------------------------------ yardımcılar

	private AppUser user(String email, String name, String phone, Instant now) {
		return users.save(new AppUser(email, encoder.encode(DEMO_PASSWORD), name, phone, now));
	}

	private Branch branch(Business b, String name, String address, String district, String city, String phone,
			Instant now) {
		Branch branch = new Branch(b.getId(), name, address, district, city, now);
		branch.describe("Soyunma odası ve kafeterya mevcut. (Kurgusal açıklama)", phone);
		return branches.save(branch);
	}

	private void weekly(Branch b, LocalTime wkOpen, LocalTime wkClose, LocalTime weOpen, LocalTime weClose) {
		for (DayOfWeek d : DayOfWeek.values()) {
			DayHours h = WEEKEND.contains(d) ? DayHours.open(weOpen, weClose) : DayHours.open(wkOpen, wkClose);
			hours.save(new BranchOpeningHours(b.getId(), d, h));
		}
	}

	private Pitch pitch(Branch b, String name, int capacity, Surface surface, String price, boolean indoor,
			int slot, int step, int buffer, Instant now) {
		Pitch p = new Pitch(b.getId(), name, capacity, surface, new BigDecimal(price), now);
		p.configureSlots(slot, step, buffer);
		p.describe("Kurgusal saha açıklaması.", capacity >= 14 ? 50 : 40, capacity >= 14 ? 30 : 20, indoor);
		p.setAmenities(true, capacity >= 12, true, true);
		return pitches.save(p);
	}

	private Reservation staffBooked(Branch b, Pitch p, LocalDate day, int hour, int minutes, AppUser customer,
			String guestName, Channel channel, AppUser staff, Instant now) {
		TimeRange play = play(day, hour, minutes);
		Reservation r = Reservation.confirmedByStaff(b.getBusinessId(), b.getId(), p.getId(), play,
				p.getBufferMinutes(), channel, customer == null ? null : customer.getId(), guestName,
				guestName == null ? null : "0555 000 99 99", null, staff.getId(), now);
		return persist(r, p);
	}

	private Reservation customerBooked(Branch b, Pitch p, LocalDate day, int hour, AppUser customer, Instant now) {
		Reservation r = customerHeld(b, p, day, hour, customer, now);
		r.confirm(now);
		return r;
	}

	private Reservation customerHeld(Branch b, Pitch p, LocalDate day, int hour, AppUser customer, Instant now) {
		Reservation r = Reservation.holdForCustomer(b.getBusinessId(), b.getId(), p.getId(),
				play(day, hour, p.getSlotMinutes()), p.getBufferMinutes(), customer.getId(),
				Duration.ofMinutes(b.getHoldMinutes()), now);
		return persist(r, p);
	}

	private Reservation persist(Reservation r, Pitch p) {
		PriceQuote q = PriceCalculator.quote(r.playRange(), IST, p.getBaseHourlyPrice(), p.getCurrency(),
				priceRules.findByPitchIdAndActiveTrue(p.getId()));
		r.applyPrice(q.total(), q.currency());
		r.snapshotDepositPolicy(branches.findById(p.getBranchId()).orElseThrow().depositPolicy());
		Reservation saved = reservations.save(r);
		for (int i = 0; i < q.lines().size(); i++) {
			priceLines.save(new ReservationPriceLine(saved.getId(), i + 1, q.lines().get(i)));
		}
		occupancy.occupy(p.getId(), saved.occupiedRange(), PitchOccupancy.Source.RESERVATION, saved.getId());
		return saved;
	}

	private void block(Pitch p, LocalDate day, int fromHour, int toHour, PitchBlock.Reason reason, String note,
			AppUser by, Instant now) {
		TimeRange range = new TimeRange(day.atTime(fromHour, 0).atZone(IST).toInstant(),
				day.atTime(toHour, 0).atZone(IST).toInstant());
		PitchBlock block = blocks.save(new PitchBlock(p.getId(), range, reason, note, by.getId(), now));
		occupancy.occupy(p.getId(), range, PitchOccupancy.Source.BLOCK, block.getId());
	}

	private void cash(Reservation r, BigDecimal amount, CashSession session, AppUser staff, Instant at) {
		payments.save(Payment.collected(r.getBusinessId(), r.getBranchId(), r.getId(), Payment.Method.CASH, amount,
				"TRY", "demo-cash-" + r.getId(), session.getId(), null, staff.getId(), at));
	}

	private void pos(Reservation r, BigDecimal amount, AppUser staff, Instant at) {
		payments.save(Payment.collected(r.getBusinessId(), r.getBranchId(), r.getId(), Payment.Method.MANUAL_POS,
				amount, "TRY", "demo-pos-" + r.getId(), null, null, staff.getId(), at));
	}

	/** Simülasyon sağlayıcısında başarılı olmuş bir çevrim içi kapora (iade denemesi için gerçek kayıtlı). */
	private void onlinePaid(Reservation r, BigDecimal amount, Instant at) {
		String key = "demo-online-" + r.getId();
		Payment p = Payment.pendingCharge(r.getBusinessId(), r.getBranchId(), r.getId(), Payment.Method.ONLINE_SIM,
				amount, "TRY", key, null, "Kapora", r.getCustomerId(), at);
		String ref = simProvider.createCharge(key, amount, "TRY", "demo").providerRef();
		jdbc.update("update sim_charge set status = 'SUCCEEDED' where provider_ref = ?", ref);
		p.attachProviderRef(ref);
		p.succeed(at);
		payments.save(p);
	}

	private static TimeRange play(LocalDate day, int hour, int minutes) {
		Instant start = day.atTime(hour, 0).atZone(IST).toInstant();
		return new TimeRange(start, start.plus(Duration.ofMinutes(minutes)));
	}

}
