package com.sahahub.community.app;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.domain.ReservationRepository;
import com.sahahub.booking.domain.ReservationStatus;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.PitchContext;
import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.ListingApplication;
import com.sahahub.community.domain.ListingApplicationRepository;
import com.sahahub.community.domain.ListingRepository;
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.NotFoundException;

/** İlan ekran modelleri. Başvuranların adları yalnızca ilan sahibine, telefonlar yalnızca kabulden sonra görünür. */
@Service
public class ListingQueries {

	public static final int PAGE = 50;

	/** Takım adı ve logosu: logo yoksa ya da takım dağıldıysa {@code logoVersion} null (bağlantıda ?v= sürümü). */
	public record TeamRef(Long id, String name, String logoVersion) {

		public boolean hasLogo() {
			return logoVersion != null;
		}

	}

	public record Card(Long id, Listing.Kind kind, String teamName, String city, String district, Instant playAt,
			String place, Listing.Level level, Integer playersNeeded, long accepted, Instant expiresAt, String note,
			Listing.Status status, boolean expired, TeamRef team) {

		public boolean open() {
			return status == Listing.Status.OPEN && !expired;
		}

		public String statusLabel() {
			return expired && status == Listing.Status.OPEN ? "Süresi doldu" : status.label();
		}

	}

	/** @param team rakip başvurusunda başvuran takım; oyuncu başvurusunda null */
	public record ApplicationRow(Long id, String applicantName, String teamName, String message,
			ListingApplication.Status status, Instant createdAt, String phone, TeamRef team) {
	}

	public record TeamOption(Long id, String name) {
	}

	public record Detail(Card card, boolean author, List<ApplicationRow> applications, ApplicationRow mine,
			String authorName, String authorPhone, boolean canApply, List<TeamOption> myCaptainTeams) {
	}

	public record MyApplication(Long id, Long listingId, String teamName, Listing.Kind kind, Instant playAt,
			ListingApplication.Status status, Instant createdAt, TeamRef team) {
	}

	public record ReservationOption(Long id, String label) {
	}

	private final ListingRepository listings;
	private final ListingApplicationRepository applications;
	private final TeamRepository teams;
	private final AppUserRepository users;
	private final ReservationRepository reservations;
	private final CatalogService catalog;
	private final Clock clock;

	public ListingQueries(ListingRepository listings, ListingApplicationRepository applications, TeamRepository teams,
			AppUserRepository users, ReservationRepository reservations, CatalogService catalog, Clock clock) {
		this.listings = listings;
		this.applications = applications;
		this.teams = teams;
		this.users = users;
		this.reservations = reservations;
		this.catalog = catalog;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Card> open(String city, Listing.Kind kind) {
		Instant now = Instant.now(clock);
		String c = city == null || city.isBlank() ? null : city;
		return cards(listings.openListings(now, c, kind, PageRequest.of(0, PAGE)), now);
	}

	@Transactional(readOnly = true)
	public List<String> cities() {
		return listings.openCities(Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public Detail detail(AppUserPrincipal user, Long id) {
		Instant now = Instant.now(clock);
		Listing l = listings.findById(id).orElseThrow(() -> new NotFoundException("İlan"));
		Card card = cards(List.of(l), now).getFirst();
		boolean author = user != null && l.getAuthorId().equals(user.id());
		List<ListingApplication> all = applications.findByListingIdOrderByCreatedAtAscIdAsc(id);
		Map<Long, AppUser> people = usersOf(all.stream().map(ListingApplication::getApplicantId).toList());
		Map<Long, TeamRef> teamNames = teamRefs(all.stream().map(ListingApplication::getApplicantTeamId).toList());
		List<ApplicationRow> rows = author ? all.stream().map(a -> row(a, people, teamNames)).toList() : List.of();
		ApplicationRow mine = null;
		if (user != null && !author) {
			mine = all.stream()
				.filter(a -> a.getApplicantId().equals(user.id()))
				.reduce((first, second) -> second) // en son başvuru
				.map(a -> row(a, people, teamNames))
				.orElse(null);
		}
		AppUser authorUser = users.findById(l.getAuthorId()).orElseThrow();
		boolean accepted = mine != null && mine.status() == ListingApplication.Status.ACCEPTED;
		boolean activeMine = mine != null && (mine.status() == ListingApplication.Status.PENDING || accepted);
		List<TeamOption> myTeams = user == null ? List.of()
				: teams.captainedBy(user.id()).stream()
					.filter(t -> !t.getId().equals(l.getTeamId()))
					.map(t -> new TeamOption(t.getId(), t.getName()))
					.toList();
		boolean canApply = user != null && !author && card.open() && !activeMine
				&& (l.getKind() == Listing.Kind.PLAYERS_WANTED || !myTeams.isEmpty());
		return new Detail(card, author, rows, mine, authorUser.getFullName(), accepted ? authorUser.getPhone() : null,
				canApply, myTeams);
	}

	@Transactional(readOnly = true)
	public List<Card> mine(AppUserPrincipal user) {
		return cards(listings.findByAuthorIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.of(0, PAGE)),
				Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public List<MyApplication> myApplications(AppUserPrincipal user) {
		List<ListingApplication> list = applications.findByApplicantIdOrderByCreatedAtDescIdDesc(user.id(),
				PageRequest.of(0, PAGE));
		Map<Long, Listing> ls = listings.findAllById(list.stream().map(ListingApplication::getListingId).toList())
			.stream()
			.collect(Collectors.toMap(Listing::getId, Function.identity()));
		Map<Long, TeamRef> refs = teamRefs(ls.values().stream().map(Listing::getTeamId).toList());
		return list.stream().map(a -> {
			Listing l = ls.get(a.getListingId());
			TeamRef team = refs.get(l.getTeamId());
			return new MyApplication(a.getId(), l.getId(), team.name(), l.getKind(), l.getPlayAt(), a.getStatus(),
					a.getCreatedAt(), team);
		}).toList();
	}

	/** İlan formunda seçilebilecek, kullanıcının onaylı ileri tarihli rezervasyonları. */
	@Transactional(readOnly = true)
	public List<ReservationOption> reservationOptions(AppUserPrincipal user) {
		Instant now = Instant.now(clock);
		return reservations
			.findUpcoming(user.id(), now, List.of(ReservationStatus.CONFIRMED), PageRequest.of(0, 30))
			.stream()
			.map(r -> {
				PitchContext ctx = catalog.pitchContext(r.getPitchId());
				return new ReservationOption(r.getId(), r.getStartsAt().atZone(ctx.branch().zone())
					.format(java.time.format.DateTimeFormatter.ofPattern("d MMM EEE HH:mm",
							java.util.Locale.forLanguageTag("tr")))
						+ " · " + ctx.pitch().getName() + " · " + ctx.branch().getName());
			})
			.toList();
	}

	// ------------------------------------------------------------------ yardımcılar

	private List<Card> cards(List<Listing> list, Instant now) {
		Map<Long, TeamRef> refs = teamRefs(list.stream().map(Listing::getTeamId).toList());
		return list.stream().map(l -> {
			String place = null;
			if (l.getReservationId() != null) {
				place = reservations.findById(l.getReservationId()).map(r -> {
					PitchContext ctx = catalog.pitchContext(r.getPitchId());
					return ctx.pitch().getName() + " · " + ctx.branch().getName();
				}).orElse(null);
			}
			TeamRef team = refs.get(l.getTeamId());
			return new Card(l.getId(), l.getKind(), team.name(), l.getCity(), l.getDistrict(), l.getPlayAt(), place,
					l.getLevel(), l.getPlayersNeeded(), applications.acceptedCount(l.getId()), l.getExpiresAt(),
					l.getNote(), l.getStatus(), !now.isBefore(l.getExpiresAt()), team);
		}).toList();
	}

	private ApplicationRow row(ListingApplication a, Map<Long, AppUser> people, Map<Long, TeamRef> teams) {
		AppUser u = people.get(a.getApplicantId());
		TeamRef team = a.getApplicantTeamId() == null ? null : teams.get(a.getApplicantTeamId());
		return new ApplicationRow(a.getId(), u.getFullName(), team == null ? null : team.name(), a.getMessage(),
				a.getStatus(), a.getCreatedAt(),
				a.getStatus() == ListingApplication.Status.ACCEPTED ? u.getPhone() : null, team);
	}

	private Map<Long, AppUser> usersOf(Collection<Long> ids) {
		return users.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
	}

	private Map<Long, TeamRef> teamRefs(Collection<Long> ids) {
		List<Long> clean = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
		return teams.findAllById(clean)
			.stream()
			.collect(Collectors.toMap(Team::getId, t -> new TeamRef(t.getId(), t.getName(), t.logoVersion())));
	}

}
