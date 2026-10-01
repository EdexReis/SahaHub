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
import com.sahahub.tournament.domain.Bracket;
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
			String city, int entries, long played, long total, Tournament.Format format) {
	}

	/**
	 * @param linkCode platform takımına bağlama kodu (bağlıyken de durur; bağlantı kaldırılınca yenilenir)
	 * @param linkedTeam bağlı platform takımının adı; bağlı değilse null
	 */
	public record EntryRow(Long id, String name, String linkCode, String linkedTeam, boolean linked) {
	}

	/** Kaptanın açtığı bağlama sayfası. */
	public record LinkPreview(String code, String tournamentName, Tournament.Format format, String statusLabel,
			String branchName, String businessName, String entryName, boolean alreadyLinked,
			List<TeamLinkPort.TeamOption> myTeams) {
	}

	/** Takım sayfasındaki "Lig ve turnuvalar" satırı. */
	public record TeamTournamentRow(Long tournamentId, String name, Tournament.Format format, String statusLabel,
			String entryName, boolean publicPage, String summary) {
	}

	public record PitchOption(Long id, String name) {
	}

	public record MatchView(Long id, int round, String home, String away, TournamentMatch.Status status,
			Long pitchId, String pitchName, ZonedDateTime start, ZonedDateTime end, Integer homeScore,
			Integer awayScore, boolean canRecord, int minutes, Long homeEntryId, Long awayEntryId, boolean knockout,
			String winner, boolean penalties) {

		public boolean played() {
			return status == TournamentMatch.Status.PLAYED;
		}

		public boolean scheduled() {
			return status != TournamentMatch.Status.UNSCHEDULED;
		}

	}

	/** Bir tur (ya da elemede üçüncülük maçı; finalle aynı tur numarasında ayrı başlık). */
	public record RoundView(int round, String name, List<MatchView> matches, boolean thirdPlace) {

		/** Sayfa içi bağlantı ve başlık kimliği için tekil ad. */
		public String anchor() {
			return thirdPlace ? "round-3rd" : "round-" + round;
		}

	}

	/** Eleme ağacında bir yer: taraflar (belli değilse null), bay, varsa maç ve sonucu. */
	public record BracketCell(String home, String away, boolean bye, Long matchId, Integer homeScore,
			Integer awayScore, String winner, boolean penalties) {
	}

	public record BracketRound(int round, String name, List<BracketCell> cells) {
	}

	public record Detail(Long id, String name, Tournament.Status status, boolean doubleRound, int pointsWin,
			int pointsDraw, int pointsLoss, Long branchId, String branchName, String businessName, String city,
			List<EntryRow> entries, List<RoundView> rounds, List<Standings.Row> standings, long unscheduled,
			long unplayed, List<PitchOption> pitches, Tournament.Format format, List<BracketRound> bracket,
			String champion, boolean thirdPlace, String third) {

		public boolean knockout() {
			return format == Tournament.Format.KNOCKOUT;
		}

		public boolean draft() {
			return status == Tournament.Status.DRAFT;
		}

		public boolean active() {
			return status == Tournament.Status.ACTIVE;
		}

		public boolean canStart() {
			return draft() && entries.size() >= minEntries();
		}

		/** Başlatmak için gereken en az takım (üçüncülük maçı varsa 4). */
		public int minEntries() {
			return knockout() && thirdPlace ? Bracket.MIN_ENTRIES_THIRD_PLACE : Tournament.MIN_ENTRIES;
		}

		public boolean canFinish() {
			return active() && unplayed == 0 && (!knockout() || (champion != null && (!thirdPlace || third != null)));
		}

		public long linkedCount() {
			return entries.stream().filter(EntryRow::linked).count();
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
	private final TeamLinkPort teamLinks;

	public TournamentQueries(TournamentRepository tournaments, TournamentEntryRepository entries,
			TournamentMatchRepository matches, CatalogService catalog, AccessGuard guard, Clock clock,
			TeamLinkPort teamLinks) {
		this.teamLinks = teamLinks;
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
		return detail(t, true);
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
		return detail(t, false);
	}

	@Transactional(readOnly = true)
	public List<ListRow> publicList() {
		return tournaments.publicTournaments().stream().map(this::row).toList();
	}

	// ------------------------------------------------------------------ platform takımı bağlantısı

	/** Bağlama sayfası (giriş yapmış kullanıcı). Bilinmeyen ya da yenilenmiş kod "bulunamadı". */
	@Transactional(readOnly = true)
	public LinkPreview linkPreview(AppUserPrincipal user, String code) {
		TournamentEntry e = entries.findByLinkCode(code).orElseThrow(() -> new NotFoundException("Bağlantı"));
		Tournament t = tournaments.findById(e.getTournamentId()).orElseThrow();
		BranchContext bc = catalog.branchContext(t.getBranchId());
		return new LinkPreview(code, t.getName(), t.getFormat(), t.getStatus().label(), bc.branch().getName(),
				bc.business().getName(), e.getName(), e.isLinked(), teamLinks.captainedTeams(user.id()));
	}

	/**
	 * Takımın bağlı olduğu lig ve turnuvalar, özetiyle: ligde sıra ve puan, kupada "Şampiyon" / "Elendi" /
	 * "Turda". Çağıran takım üyeliğini denetler.
	 */
	@Transactional(readOnly = true)
	public List<TeamTournamentRow> forTeam(Long teamId) {
		List<TeamTournamentRow> rows = new ArrayList<>();
		for (TournamentEntry e : entries.findByTeamIdOrderByIdDesc(teamId)) {
			Tournament t = tournaments.findById(e.getTournamentId()).orElseThrow();
			String summary;
			if (t.isDraft()) {
				summary = "Fikstür bekleniyor";
			}
			else if (t.isKnockout()) {
				List<TournamentMatch> ms = matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId());
				boolean lost = ms.stream().anyMatch(m -> m.isPlayed() && m.getWinnerEntryId() != null
						&& (m.getHomeEntryId().equals(e.getId()) || m.getAwayEntryId().equals(e.getId()))
						&& !m.getWinnerEntryId().equals(e.getId())
						&& !Bracket.isThirdPlace(m.getRound(), m.getBracketSlot(),
								Bracket.rounds((int) entries.countByTournamentId(t.getId()))));
				Detail d = detail(t, false);
				summary = e.getName().equals(d.champion()) ? "Şampiyon"
						: e.getName().equals(d.third()) ? "Üçüncü" : lost ? "Elendi" : "Turda";
			}
			else {
				summary = detail(t, false).standings().stream()
					.filter(r -> r.name().equals(e.getName()))
					.findFirst()
					.map(r -> r.position() + ". sıra · " + r.points() + " puan · " + r.played() + " maç")
					.orElse("");
			}
			rows.add(new TeamTournamentRow(t.getId(), t.getName(), t.getFormat(), t.getStatus().label(), e.getName(),
					t.isPublic(), summary));
		}
		return rows;
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
		Map<Long, Tournament> leagues = tournaments
			.findAllById(list.stream().map(TournamentMatch::getTournamentId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Tournament::getId, java.util.function.Function.identity()));
		Map<Long, String> leagueNames = leagues.values().stream()
			.collect(Collectors.toMap(Tournament::getId, Tournament::getName));
		return list.stream()
			.map(m -> new MatchSlot(m.getPitchId(), m.play(),
					names.get(m.getHomeEntryId()) + " – " + names.get(m.getAwayEntryId())
							+ (m.isPlayed() ? " (" + m.getHomeScore() + "-" + m.getAwayScore() + ")" : ""),
					leagueNames.get(m.getTournamentId()) + " · " + roundLabel(leagues.get(m.getTournamentId()), m),
					"/ligler/" + m.getTournamentId()))
			.toList();
	}

	// ------------------------------------------------------------------ yardımcılar

	private String roundLabel(Tournament t, TournamentMatch m) {
		if (t.isKnockout()) {
			int n = (int) entries.countByTournamentId(t.getId());
			return Bracket.matchName(m.getRound(), m.getBracketSlot(), n >= 2 ? Bracket.rounds(n) : 1);
		}
		return m.getRound() + ". hafta";
	}

	private List<EntryRow> entryRows(List<TournamentEntry> es, boolean staff) {
		Map<Long, String> teamNames = teamLinks.activeTeamNames(
				es.stream().map(TournamentEntry::getTeamId).filter(java.util.Objects::nonNull).toList());
		return es.stream()
			.map(e -> new EntryRow(e.getId(), e.getName(), staff ? e.getLinkCode() : null,
					e.isLinked() ? teamNames.getOrDefault(e.getTeamId(), "Dağılmış takım") : null, e.isLinked()))
			.toList();
	}

	private ListRow row(Tournament t) {
		BranchContext bc = catalog.branchContext(t.getBranchId());
		List<TournamentMatch> ms = matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId());
		int n = (int) entries.countByTournamentId(t.getId());
		// Elemede maçlar sonuçlar geldikçe açılır; toplam, oynanacak maç sayısıdır (takım − 1, üçüncülük varsa +1)
		long total = t.isKnockout() && t.getStatus() != Tournament.Status.DRAFT
				? TournamentService.knockoutMatchCount(t, n) : ms.size();
		return new ListRow(t.getId(), t.getName(), t.getStatus(), bc.branch().getName(), bc.business().getName(),
				bc.branch().getCity(), n, ms.stream().filter(TournamentMatch::isPlayed).count(), total, t.getFormat());
	}

	/** @param staff bağlantı kodları yalnızca personel görünümünde yer alır (herkese açık sayfaya hiç girmez) */
	private Detail detail(Tournament t, boolean staff) {
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
		int totalRounds = es.size() >= 2 ? Bracket.rounds(es.size()) : 1;
		java.util.Set<Long> thirdIds = ms.stream()
			.filter(m -> t.isKnockout() && Bracket.isThirdPlace(m.getRound(), m.getBracketSlot(), totalRounds))
			.map(TournamentMatch::getId)
			.collect(Collectors.toSet());
		for (TournamentMatch m : ms) {
			int minutes = m.play() == null ? 60 : (int) Duration.between(m.getStartsAt(), m.getEndsAt()).toMinutes();
			byRound.computeIfAbsent(m.getRound(), k -> new ArrayList<>())
				.add(new MatchView(m.getId(), m.getRound(), names.get(m.getHomeEntryId()),
						names.get(m.getAwayEntryId()), m.getStatus(), m.getPitchId(),
						m.getPitchId() == null ? null : pitchNames.getOrDefault(m.getPitchId(), "Saha"),
						m.getStartsAt() == null ? null : m.getStartsAt().atZone(zone),
						m.getEndsAt() == null ? null : m.getEndsAt().atZone(zone), m.getHomeScore(), m.getAwayScore(),
						t.isActive() && m.canRecordResultAt(now), minutes, m.getHomeEntryId(), m.getAwayEntryId(),
						m.isKnockout(), m.getWinnerEntryId() == null ? null : names.get(m.getWinnerEntryId()),
						m.isDecidedByPenalties()));
			if (!t.isKnockout() && m.isPlayed()) {
				results.add(new Standings.Result(m.getHomeEntryId(), m.getAwayEntryId(), m.getHomeScore(),
						m.getAwayScore()));
			}
		}
		List<RoundView> rounds = new ArrayList<>();
		for (var e : byRound.entrySet()) {
			if (!t.isKnockout()) {
				rounds.add(new RoundView(e.getKey(), e.getKey() + ". hafta", e.getValue(), false));
				continue;
			}
			// Üçüncülük maçı finalden önce, kendi başlığıyla
			List<MatchView> third = e.getValue().stream().filter(m -> thirdIds.contains(m.id())).toList();
			List<MatchView> rest = e.getValue().stream().filter(m -> !thirdIds.contains(m.id())).toList();
			if (!third.isEmpty()) {
				rounds.add(new RoundView(e.getKey(), Bracket.THIRD_PLACE_NAME, third, true));
			}
			if (!rest.isEmpty()) {
				rounds.add(new RoundView(e.getKey(), Bracket.roundName(e.getKey(), totalRounds), rest, false));
			}
		}
		List<BracketRound> bracket = new ArrayList<>();
		String champion = null;
		String third = null;
		if (t.isKnockout() && es.size() >= 2 && !t.isDraft()) {
			Map<String, TournamentMatch> bySlot = new java.util.HashMap<>();
			Map<String, Long> winners = new java.util.HashMap<>();
			for (TournamentMatch m : ms) {
				String key = Bracket.key(m.getRound(), m.getBracketSlot());
				bySlot.put(key, m);
				if (m.isPlayed()) {
					winners.put(key, m.getWinnerEntryId());
				}
			}
			for (List<Bracket.Slot> round : Bracket.build(es.stream().map(TournamentEntry::getId).toList(), winners,
					t.isThirdPlace())) {
				List<BracketCell> cells = new ArrayList<>();
				BracketCell thirdCell = null;
				for (Bracket.Slot sl : round) {
					TournamentMatch m = bySlot.get(Bracket.key(sl.round(), sl.slot()));
					BracketCell cell = new BracketCell(sl.home() == null ? null : names.get(sl.home()),
							sl.away() == null ? null : names.get(sl.away()), sl.bye(), m == null ? null : m.getId(),
							m == null ? null : m.getHomeScore(), m == null ? null : m.getAwayScore(),
							m == null || m.getWinnerEntryId() == null ? null : names.get(m.getWinnerEntryId()),
							m != null && m.isDecidedByPenalties());
					if (Bracket.isThirdPlace(sl.round(), sl.slot(), totalRounds)) {
						thirdCell = cell;
					}
					else {
						cells.add(cell);
					}
				}
				bracket.add(new BracketRound(round.getFirst().round(),
						Bracket.roundName(round.getFirst().round(), totalRounds), cells));
				if (thirdCell != null) {
					// Ağaçta finalden sonra ayrı sütun
					bracket.add(new BracketRound(round.getFirst().round(), Bracket.THIRD_PLACE_NAME, List.of(thirdCell)));
				}
			}
			Long winner = winners.get(Bracket.key(totalRounds, 0));
			champion = winner == null ? null : names.get(winner);
			Long thirdWinner = winners.get(Bracket.key(totalRounds, Bracket.THIRD_PLACE_SLOT));
			third = thirdWinner == null ? null : names.get(thirdWinner);
		}
		return new Detail(t.getId(), t.getName(), t.getStatus(), t.isDoubleRound(), t.getPointsWin(),
				t.getPointsDraw(), t.getPointsLoss(), t.getBranchId(), bc.branch().getName(), bc.business().getName(),
				bc.branch().getCity(), entryRows(es, staff), rounds,
				Standings.compute(names, results, t.points()),
				ms.stream().filter(m -> m.getStatus() == TournamentMatch.Status.UNSCHEDULED).count(),
				ms.stream().filter(m -> !m.isPlayed()).count(),
				pitches.stream().map(p -> new PitchOption(p.getId(), p.getName())).toList(), t.getFormat(), bracket,
				champion, t.isThirdPlace(), third);
	}

}
