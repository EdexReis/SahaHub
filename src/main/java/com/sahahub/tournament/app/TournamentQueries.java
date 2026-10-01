package com.sahahub.tournament.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.MatchCalendarPort;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.Pitch;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;
import com.sahahub.tournament.domain.Standings;
import com.sahahub.tournament.domain.Tournament;
import com.sahahub.tournament.domain.TournamentEntry;
import com.sahahub.tournament.domain.TournamentEntryRepository;
import com.sahahub.tournament.domain.TournamentMatch;
import com.sahahub.tournament.domain.TournamentMatchRepository;
import com.sahahub.tournament.domain.TournamentRepository;

/** Lig ekran modelleri (personel ve herkese açık) ve personel takvimi için maç bilgisi. */
@Service
public class TournamentQueries implements MatchCalendarPort {

	public record ListRow(Long id, String name, Tournament.Status status, String branchName, String businessName,
			String city, int entries, long played, long total) {
	}

	public record EntryRow(Long id, String name) {
	}

	public record PitchOption(Long id, String name) {
	}

	public record MatchView(Long id, int round, String home, String away, TournamentMatch.Status status,
			Long pitchId, String pitchName, ZonedDateTime start, ZonedDateTime end, Integer homeScore,
			Integer awayScore, boolean canRecord, int minutes) {

		public boolean played() {
			return status == TournamentMatch.Status.PLAYED;
		}

		public boolean scheduled() {
			return status != TournamentMatch.Status.UNSCHEDULED;
		}

	}

	public record RoundView(int round, List<MatchView> matches) {
	}

	public record Detail(Long id, String name, Tournament.Status status, boolean doubleRound, int pointsWin,
			int pointsDraw, int pointsLoss, Long branchId, String branchName, String businessName, String city,
			List<EntryRow> entries, List<RoundView> rounds, List<Standings.Row> standings, long unscheduled,
			long unplayed, List<PitchOption> pitches) {

		public boolean draft() {
			return status == Tournament.Status.DRAFT;
		}

		public boolean active() {
			return status == Tournament.Status.ACTIVE;
		}

		public boolean canStart() {
			return draft() && entries.size() >= Tournament.MIN_ENTRIES;
		}

		public boolean canFinish() {
			return active() && unplayed == 0;
		}

		public int matchCount() {
			return rounds.stream().mapToInt(r -> r.matches().size()).sum();
		}

	}

	private final TournamentRepository tournaments;
	private final TournamentEntryRepository entries;
	private final TournamentMatchRepository matches;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final Clock clock;

	public TournamentQueries(TournamentRepository tournaments, TournamentEntryRepository entries,
			TournamentMatchRepository matches, CatalogService catalog, AccessGuard guard, Clock clock) {
		this.tournaments = tournaments;
		this.entries = entries;
		this.matches = matches;
		this.catalog = catalog;
		this.guard = guard;
		this.clock = clock;
	}

	/** Şubenin ligleri (personel). */
	@Transactional(readOnly = true)
	public List<ListRow> forBranch(AppUserPrincipal user, Long branchId) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.TOURNAMENT_MANAGE);
		return tournaments.findByBranchIdOrderByCreatedAtDesc(branchId).stream().map(this::row).toList();
	}

	@Transactional(readOnly = true)
	public Detail forStaff(AppUserPrincipal user, Long tournamentId) {
		Tournament t = tournaments.findById(tournamentId).orElseThrow(() -> new NotFoundException("Lig"));
		guard.requireBranch(user, t.getBusinessId(), t.getBranchId(), Permission.TOURNAMENT_MANAGE);
		return detail(t);
	}

	/** Herkese açık lig sayfası. Taslak lig ve askıdaki işletmenin ligi "bulunamadı" döner. */
	@Transactional(readOnly = true)
	public Detail forPublic(Long tournamentId) {
		Tournament t = tournaments.findById(tournamentId)
			.filter(Tournament::isPublic)
			.orElseThrow(() -> new NotFoundException("Lig"));
		BranchContext bc = catalog.branchContext(t.getBranchId());
		if (!bc.business().isActive() || bc.branch().isArchived()) {
			throw new NotFoundException("Lig");
		}
		return detail(t);
	}

	@Transactional(readOnly = true)
	public List<ListRow> publicList() {
		return tournaments.publicTournaments().stream().map(this::row).toList();
	}

	// ------------------------------------------------------------------ takvim portu

	@Override
	@Transactional(readOnly = true)
	public List<MatchSlot> matches(Collection<Long> pitchIds, TimeRange range) {
		if (pitchIds.isEmpty()) {
			return List.of();
		}
		List<TournamentMatch> list = matches.scheduledOverlapping(pitchIds, range.start(), range.end());
		if (list.isEmpty()) {
			return List.of();
		}
		Map<Long, String> names = entries
			.findAllById(list.stream().flatMap(m -> java.util.stream.Stream.of(m.getHomeEntryId(), m.getAwayEntryId()))
				.distinct()
				.toList())
			.stream()
			.collect(Collectors.toMap(TournamentEntry::getId, TournamentEntry::getName));
		Map<Long, String> leagueNames = tournaments
			.findAllById(list.stream().map(TournamentMatch::getTournamentId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Tournament::getId, Tournament::getName));
		return list.stream()
			.map(m -> new MatchSlot(m.getPitchId(), m.play(),
					names.get(m.getHomeEntryId()) + " – " + names.get(m.getAwayEntryId())
							+ (m.isPlayed() ? " (" + m.getHomeScore() + "-" + m.getAwayScore() + ")" : ""),
					leagueNames.get(m.getTournamentId()) + " · " + m.getRound() + ". hafta",
					"/ligler/" + m.getTournamentId()))
			.toList();
	}

	// ------------------------------------------------------------------ yardımcılar

	private ListRow row(Tournament t) {
		BranchContext bc = catalog.branchContext(t.getBranchId());
		List<TournamentMatch> ms = matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId());
		return new ListRow(t.getId(), t.getName(), t.getStatus(), bc.branch().getName(), bc.business().getName(),
				bc.branch().getCity(), (int) entries.countByTournamentId(t.getId()),
				ms.stream().filter(TournamentMatch::isPlayed).count(), ms.size());
	}

	private Detail detail(Tournament t) {
		BranchContext bc = catalog.branchContext(t.getBranchId());
		ZoneId zone = bc.branch().zone();
		Instant now = Instant.now(clock);
		List<TournamentEntry> es = entries.findByTournamentIdOrderById(t.getId());
		Map<Long, String> names = es.stream().collect(Collectors.toMap(TournamentEntry::getId, TournamentEntry::getName));
		List<Pitch> pitches = catalog.activePitches(t.getBranchId());
		Map<Long, String> pitchNames = pitches.stream().collect(Collectors.toMap(Pitch::getId, Pitch::getName));
		List<TournamentMatch> ms = matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId());

		Map<Integer, List<MatchView>> byRound = new TreeMap<>();
		List<Standings.Result> results = new ArrayList<>();
		for (TournamentMatch m : ms) {
			int minutes = m.play() == null ? 60 : (int) Duration.between(m.getStartsAt(), m.getEndsAt()).toMinutes();
			byRound.computeIfAbsent(m.getRound(), k -> new ArrayList<>())
				.add(new MatchView(m.getId(), m.getRound(), names.get(m.getHomeEntryId()),
						names.get(m.getAwayEntryId()), m.getStatus(), m.getPitchId(),
						m.getPitchId() == null ? null : pitchNames.getOrDefault(m.getPitchId(), "Saha"),
						m.getStartsAt() == null ? null : m.getStartsAt().atZone(zone),
						m.getEndsAt() == null ? null : m.getEndsAt().atZone(zone), m.getHomeScore(), m.getAwayScore(),
						t.isActive() && m.canRecordResultAt(now), minutes));
			if (m.isPlayed()) {
				results.add(new Standings.Result(m.getHomeEntryId(), m.getAwayEntryId(), m.getHomeScore(),
						m.getAwayScore()));
			}
		}
		List<RoundView> rounds = byRound.entrySet().stream().map(e -> new RoundView(e.getKey(), e.getValue())).toList();
		return new Detail(t.getId(), t.getName(), t.getStatus(), t.isDoubleRound(), t.getPointsWin(),
				t.getPointsDraw(), t.getPointsLoss(), t.getBranchId(), bc.branch().getName(), bc.business().getName(),
				bc.branch().getCity(), es.stream().map(e -> new EntryRow(e.getId(), e.getName())).toList(), rounds,
				Standings.compute(names, results, t.points()),
				ms.stream().filter(m -> m.getStatus() == TournamentMatch.Status.UNSCHEDULED).count(),
				ms.stream().filter(m -> !m.isPlayed()).count(),
				pitches.stream().map(p -> new PitchOption(p.getId(), p.getName())).toList());
	}

}
