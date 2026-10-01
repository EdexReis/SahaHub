package com.sahahub.tournament.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.booking.app.BookingEvents;
import com.sahahub.booking.app.OccupancyService;
import com.sahahub.booking.domain.PitchOccupancy;
import com.sahahub.booking.domain.SlotUnavailableException;
import com.sahahub.business.app.CatalogService;
import com.sahahub.business.app.CatalogService.BranchContext;
import com.sahahub.business.domain.BranchSchedule;
import com.sahahub.business.domain.Pitch;
import com.sahahub.identity.access.AccessGuard;
import com.sahahub.identity.domain.Permission;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.audit.AuditService;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;
import com.sahahub.shared.domain.TimeRange;
import com.sahahub.tournament.domain.Bracket;
import com.sahahub.tournament.domain.RoundRobin;
import com.sahahub.tournament.domain.Tournament;
import com.sahahub.tournament.domain.TournamentEntry;
import com.sahahub.tournament.domain.TournamentEntryRepository;
import com.sahahub.tournament.domain.TournamentMatch;
import com.sahahub.tournament.domain.TournamentMatchRepository;
import com.sahahub.tournament.domain.TournamentRepository;

/**
 * Lig yönetimi (personel). Her işlem {@link Permission#TOURNAMENT_MANAGE} ve ligin şubesine atanmış olmayı
 * ister; ligin şubesi veritabanından okunur.
 * <p>
 * Maç planlamak sahayı {@code pitch_occupancy} üzerinden meşgul eder: rezervasyonla aynı EXCLUDE kısıtı
 * ve aynı advisory lock. Dolu saate maç konamaz, maç olan saate rezervasyon yapılamaz (senaryo 14).
 * Yeniden planlamada eski doluluk aynı transaction'da kapatılır; yeni saat doluysa her şey geri alınır.
 */
@Service
public class TournamentService {

	public static final int MIN_MATCH_MINUTES = 30;
	public static final int MAX_MATCH_MINUTES = 180;

	private final TournamentRepository tournaments;
	private final TournamentEntryRepository entries;
	private final TournamentMatchRepository matches;
	private final CatalogService catalog;
	private final AccessGuard guard;
	private final OccupancyService occupancy;
	private final AuditService audit;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final TeamLinkPort teamLinks;

	public TournamentService(TournamentRepository tournaments, TournamentEntryRepository entries,
			TournamentMatchRepository matches, CatalogService catalog, AccessGuard guard, OccupancyService occupancy,
			AuditService audit, ApplicationEventPublisher events, Clock clock, TeamLinkPort teamLinks) {
		this.teamLinks = teamLinks;
		this.tournaments = tournaments;
		this.entries = entries;
		this.matches = matches;
		this.catalog = catalog;
		this.guard = guard;
		this.occupancy = occupancy;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ lig ve takımlar

	@Transactional
	public Long create(AppUserPrincipal user, Long branchId, String name, boolean doubleRound, int win, int draw,
			int loss) {
		return create(user, branchId, name, Tournament.Format.LEAGUE, doubleRound, win, draw, loss);
	}

	@Transactional
	public Long create(AppUserPrincipal user, Long branchId, String name, Tournament.Format format,
			boolean doubleRound, int win, int draw, int loss) {
		return create(user, branchId, name, format, doubleRound, win, draw, loss, false);
	}

	/** @param thirdPlace yalnızca eleme turnuvasında dikkate alınır (formda "yalnızca kupa" diye belirtilir) */
	@Transactional
	public Long create(AppUserPrincipal user, Long branchId, String name, Tournament.Format format,
			boolean doubleRound, int win, int draw, int loss, boolean thirdPlace) {
		BranchContext bc = catalog.branchContext(branchId);
		guard.requireBranch(user, bc.business().getId(), branchId, Permission.TOURNAMENT_MANAGE);
		if (name == null || name.isBlank() || name.strip().length() > 80) {
			throw new BusinessRuleException("Lig adı 1-80 karakter olmalı.");
		}
		if (win < draw || draw < loss || win > 10 || loss < 0) {
			throw new BusinessRuleException("Puanlar 0-10 arası ve galibiyet ≥ beraberlik ≥ mağlubiyet olmalı.");
		}
		Tournament.Format f = format == null ? Tournament.Format.LEAGUE : format;
		Tournament t = tournaments.save(new Tournament(bc.business().getId(), branchId, name, f,
				f == Tournament.Format.LEAGUE && doubleRound, win, draw, loss, user.id(), Instant.now(clock)));
		if (f == Tournament.Format.KNOCKOUT && thirdPlace) {
			t.changeThirdPlace(true);
		}
		audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_CREATED", "Tournament", t.getId(), t.getName());
		return t.getId();
	}

	@Transactional
	public void addEntry(AppUserPrincipal user, Long tournamentId, String name) {
		Tournament t = lockedForManage(user, tournamentId);
		if (!t.isDraft()) {
			throw new BusinessRuleException("Fikstür oluşturulduktan sonra takım eklenemez.");
		}
		if (name == null || name.isBlank() || name.strip().length() > 60) {
			throw new BusinessRuleException("Takım adı 1-60 karakter olmalı.");
		}
		if (entries.countByTournamentId(tournamentId) >= t.maxEntries()) {
			throw new BusinessRuleException("Bu turnuvada en fazla " + t.maxEntries() + " takım olabilir.");
		}
		try {
			entries.saveAndFlush(new TournamentEntry(tournamentId, name, TournamentEntry.newLinkCode(), Instant.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu adda bir takım ligde zaten var.");
		}
	}

	/** Üçüncülük maçını açar veya kapatır (yalnızca eleme turnuvası, eşleşmeler oluşturulmadan önce). */
	@Transactional
	public void changeThirdPlace(AppUserPrincipal user, Long tournamentId, boolean on) {
		Tournament t = lockedForManage(user, tournamentId);
		if (!t.isKnockout()) {
			throw new BusinessRuleException("Üçüncülük maçı yalnızca eleme turnuvasında olur.");
		}
		if (!t.isDraft()) {
			throw new BusinessRuleException("Eşleşmeler oluşturulduktan sonra üçüncülük maçı değiştirilemez.");
		}
		if (t.isThirdPlace() != on) {
			t.changeThirdPlace(on);
			audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_THIRD_PLACE_CHANGED", "Tournament", t.getId(),
					"thirdPlace=" + on);
		}
	}

	@Transactional
	public void removeEntry(AppUserPrincipal user, Long tournamentId, Long entryId) {
		Tournament t = lockedForManage(user, tournamentId);
		if (!t.isDraft()) {
			throw new BusinessRuleException("Fikstür oluşturulduktan sonra takım çıkarılamaz.");
		}
		TournamentEntry e = entries.findById(entryId)
			.filter(x -> x.getTournamentId().equals(tournamentId))
			.orElseThrow(() -> new NotFoundException("Takım"));
		entries.delete(e);
	}

	/** Fikstürü üretir ve ligi başlatır. Lig satırı kilitli olduğu için iki kez üretilemez. */
	@Transactional
	public int start(AppUserPrincipal user, Long tournamentId) {
		Tournament t = lockedForManage(user, tournamentId);
		if (!t.isDraft()) {
			throw new BusinessRuleException("Fikstür zaten oluşturuldu.");
		}
		List<TournamentEntry> list = entries.findByTournamentIdOrderById(tournamentId);
		if (list.size() < Tournament.MIN_ENTRIES) {
			throw new BusinessRuleException("Fikstür için en az " + Tournament.MIN_ENTRIES + " takım gerekli.");
		}
		if (t.isKnockout() && t.isThirdPlace() && list.size() < Bracket.MIN_ENTRIES_THIRD_PLACE) {
			throw new BusinessRuleException("Üçüncülük maçı için en az " + Bracket.MIN_ENTRIES_THIRD_PLACE
					+ " takım gerekli. Takım ekleyin ya da üçüncülük maçını kapatın.");
		}
		int created;
		if (t.isKnockout()) {
			// İlk turun bay olmayan maçları; sonraki turlar sonuçlar geldikçe açılır
			created = advance(t);
		}
		else {
			List<RoundRobin.Pairing> pairings = RoundRobin.generate(
					list.stream().map(TournamentEntry::getId).toList(), t.isDoubleRound());
			for (RoundRobin.Pairing p : pairings) {
				matches.save(new TournamentMatch(tournamentId, p.round(), p.home(), p.away()));
			}
			created = pairings.size();
		}
		t.start(Instant.now(clock));
		audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_STARTED", "Tournament", t.getId(),
				"format=" + t.getFormat() + ", entries=" + list.size() + ", matches=" + created);
		return created;
	}

	@Transactional
	public void finish(AppUserPrincipal user, Long tournamentId) {
		Tournament t = lockedForManage(user, tournamentId);
		if (!t.isActive()) {
			throw new BusinessRuleException("Yalnızca süren lig bitirilebilir.");
		}
		long left = matches.unplayedCount(tournamentId);
		if (left > 0) {
			throw new BusinessRuleException("Henüz oynanmamış " + left + " maç var.");
		}
		if (t.isKnockout() && matches.findByTournamentIdOrderByRoundAscIdAsc(tournamentId).size()
				< knockoutMatchCount(t, (int) entries.countByTournamentId(tournamentId))) {
			throw new BusinessRuleException(t.isThirdPlace() ? "Final ve üçüncülük maçı henüz oynanmadı." : "Final henüz oynanmadı.");
		}
		t.finish(Instant.now(clock));
		audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_FINISHED", "Tournament", t.getId(), null);
	}

	// ------------------------------------------------------------------ maç planlama

	/** Tek maçı planlar veya yeniden planlar. Saat doluysa hata; yeniden planlamada eski saat korunur. */
	@Transactional
	public void schedule(AppUserPrincipal user, Long matchId, Long pitchId, LocalDateTime start, int minutes) {
		TournamentMatch m = matches.findById(matchId).orElseThrow(() -> new NotFoundException("Maç"));
		Tournament t = lockedForManage(user, m.getTournamentId());
		requireActive(t);
		if (m.isPlayed()) {
			throw new BusinessRuleException("Oynanmış maçın saati değiştirilemez.");
		}
		Pitch pitch = pitchOf(t, pitchId);
		ZoneId zone = catalog.branchContext(t.getBranchId()).branch().zone();
		TimeRange play = playRange(start, minutes, zone);
		requireFutureAndOpen(t, play, zone);
		TimeRange old = m.play();
		Long oldPitch = m.getPitchId();
		if (m.getStatus() == TournamentMatch.Status.SCHEDULED) {
			occupancy.release(PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
		}
		m.schedule(pitch.getId(), play);
		try {
			occupancy.occupy(pitch.getId(), play, PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
		}
		catch (SlotUnavailableException ex) {
			throw new BusinessRuleException(pitch.getName() + " bu saatte dolu (rezervasyon, kapatma veya başka maç)."
					+ (old != null ? " Maç eski saatinde kaldı." : ""));
		}
		if (old != null) {
			events.publishEvent(new BookingEvents.PitchFreed(oldPitch, old.start(), old.end()));
		}
		changed(t, m);
		audit.record(user.id(), t.getBusinessId(), old == null ? "MATCH_SCHEDULED" : "MATCH_RESCHEDULED",
				"TournamentMatch", m.getId(), "pitch=" + pitch.getId() + ", start=" + play.start());
	}

	@Transactional
	public void unschedule(AppUserPrincipal user, Long matchId) {
		TournamentMatch m = matches.findById(matchId).orElseThrow(() -> new NotFoundException("Maç"));
		Tournament t = lockedForManage(user, m.getTournamentId());
		requireActive(t);
		if (m.getStatus() != TournamentMatch.Status.SCHEDULED) {
			throw new BusinessRuleException("Yalnızca planlanmış (oynanmamış) maçın planı kaldırılır.");
		}
		TimeRange old = m.play();
		Long oldPitch = m.getPitchId();
		occupancy.release(PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
		m.unschedule();
		events.publishEvent(new BookingEvents.PitchFreed(oldPitch, old.start(), old.end()));
		changed(t, m);
		audit.record(user.id(), t.getBusinessId(), "MATCH_UNSCHEDULED", "TournamentMatch", m.getId(), null);
	}

	/**
	 * Planlanmamış maçları haftalık yerleştirir: k. haftanın maçları ilk tarih + (k−1) hafta günü, verilen
	 * sahada başlangıç saatinden itibaren art arda. Tek transaction: bir maçın saati doluysa hiçbir maç
	 * planlanmaz ve hata hangi maç olduğunu söyler.
	 *
	 * @return planlanan maç sayısı
	 */
	@Transactional
	public int planWeekly(AppUserPrincipal user, Long tournamentId, LocalDate firstDate, LocalTime firstStart,
			int minutes, Long pitchId) {
		Tournament t = lockedForManage(user, tournamentId);
		requireActive(t);
		if (firstDate == null || firstStart == null) {
			throw new BusinessRuleException("İlk tarih ve başlangıç saatini seçin.");
		}
		Pitch pitch = pitchOf(t, pitchId);
		ZoneId zone = catalog.branchContext(t.getBranchId()).branch().zone();
		List<TournamentMatch> open = matches.findByTournamentIdOrderByRoundAscIdAsc(tournamentId).stream()
			.filter(m -> m.getStatus() == TournamentMatch.Status.UNSCHEDULED)
			.toList();
		if (open.isEmpty()) {
			throw new BusinessRuleException("Planlanacak maç yok.");
		}
		Map<Long, String> names = entryNames(tournamentId);
		int firstRound = open.getFirst().getRound();
		Map<Integer, List<TournamentMatch>> byRound = open.stream()
			.collect(Collectors.groupingBy(TournamentMatch::getRound, TreeMap::new, Collectors.toList()));
		int count = 0;
		for (var e : byRound.entrySet()) {
			LocalDate day = firstDate.plusWeeks(e.getKey() - firstRound);
			LocalDateTime at = day.atTime(firstStart);
			for (TournamentMatch m : e.getValue()) {
				TimeRange play = playRange(at, minutes, zone);
				String label = e.getKey() + ". hafta " + names.get(m.getHomeEntryId()) + " – "
						+ names.get(m.getAwayEntryId()) + " (" + at.toLocalDate() + " " + at.toLocalTime() + ")";
				if (!play.start().isAfter(Instant.now(clock))) {
					throw new BusinessRuleException(label + ": geçmiş saat. Hiçbir maç planlanmadı.");
				}
				if (!catalog.schedule(catalog.branchContext(t.getBranchId()).branch(), day.minusDays(1), day)
					.isOpenDuring(play)) {
					throw new BusinessRuleException(label + ": şube bu saatte kapalı. Hiçbir maç planlanmadı.");
				}
				m.schedule(pitch.getId(), play);
				try {
					occupancy.occupy(pitch.getId(), play, PitchOccupancy.Source.TOURNAMENT_MATCH, m.getId());
				}
				catch (SlotUnavailableException ex) {
					throw new BusinessRuleException(label + ": saha dolu. Hiçbir maç planlanmadı.");
				}
				changed(t, m);
				count++;
				at = at.plusMinutes(minutes);
			}
		}
		audit.record(user.id(), t.getBusinessId(), "MATCHES_PLANNED", "Tournament", t.getId(),
				"pitch=" + pitch.getId() + ", count=" + count + ", from=" + firstDate);
		return count;
	}

	// ------------------------------------------------------------------ skor

	@Transactional
	public void recordResult(AppUserPrincipal user, Long matchId, Integer home, Integer away) {
		recordResult(user, matchId, home, away, null);
	}

	/**
	 * Skor girer veya düzeltir. Eleme maçında beraberlikte penaltı galibi zorunludur; sonuç girilince ağaçta
	 * iki tarafı belli olan sonraki tur maçı açılır. Galibi değiştiren düzeltme, sonraki tur maçı oynandıysa
	 * reddedilir; oynanmadıysa o maçın takımı güncellenir.
	 *
	 * @param penaltyWinner yalnızca eleme maçında, skor eşitse: penaltıları kazanan takımın kaydı
	 */
	@Transactional
	public void recordResult(AppUserPrincipal user, Long matchId, Integer home, Integer away, Long penaltyWinner) {
		Long tournamentId = matches.tournamentIdOf(matchId).orElseThrow(() -> new NotFoundException("Maç"));
		Tournament t = lockedForManage(user, tournamentId);
		TournamentMatch m = matches.findById(matchId).orElseThrow();
		requireActive(t);
		if (home == null || away == null || home < 0 || away < 0 || home > 99 || away > 99) {
			throw new BusinessRuleException("Skor 0-99 arasında iki sayı olmalı.");
		}
		if (!m.canRecordResultAt(Instant.now(clock))) {
			throw new BusinessRuleException(m.getStatus() == TournamentMatch.Status.UNSCHEDULED
					? "Önce maçı planlayın." : "Maç başlamadan skor girilemez.");
		}
		boolean penalties = m.isKnockout() && home.equals(away);
		if (penalties && (penaltyWinner == null
				|| (!penaltyWinner.equals(m.getHomeEntryId()) && !penaltyWinner.equals(m.getAwayEntryId())))) {
			throw new BusinessRuleException("Eleme maçı berabere bitemez: penaltıları kazanan takımı seçin.");
		}
		boolean correction = m.isPlayed();
		String before = correction ? m.getHomeScore() + "-" + m.getAwayScore() : null;
		if (m.isKnockout() && correction) {
			Long newWinner = penalties ? penaltyWinner : (home > away ? m.getHomeEntryId() : m.getAwayEntryId());
			if (!newWinner.equals(m.getWinnerEntryId()) && nextMatchPlayed(t, m)) {
				throw new BusinessRuleException("Galibi değiştiren düzeltme yapılamaz: bu maçın sonucuna bağlı sonraki tur maçı"
						+ (t.isThirdPlace() ? " ya da üçüncülük maçı" : "") + " oynandı.");
			}
		}
		if (penalties) {
			m.recordPenaltyResult(home, penaltyWinner, Instant.now(clock));
		}
		else {
			m.recordResult(home, away, Instant.now(clock));
		}
		audit.record(user.id(), t.getBusinessId(), correction ? "MATCH_RESULT_CORRECTED" : "MATCH_RESULT_RECORDED",
				"TournamentMatch", m.getId(), (before != null ? before + " → " : "") + home + "-" + away
						+ (penalties ? " (pen. " + penaltyWinner + ")" : ""));
		if (t.isKnockout()) {
			matches.flush();
			advance(t);
		}
		changed(t, m);
	}

	/**
	 * Eleme ağacını oynanmış maçlara göre yeniden hesaplar: iki tarafı belli olup henüz satırı olmayan
	 * yerler için maç açar, takımı değişmiş (düzeltme) ama oynanmamış maçların takımlarını günceller.
	 * Turnuva satırı çağıranda kilitlidir; tekil indeks aynı yere ikinci maçı ayrıca engeller.
	 *
	 * @return açılan yeni maç sayısı
	 */
	private int advance(Tournament t) {
		List<Long> seeds = entries.findByTournamentIdOrderById(t.getId()).stream().map(TournamentEntry::getId).toList();
		List<TournamentMatch> existing = matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId());
		Map<String, TournamentMatch> bySlot = new java.util.HashMap<>();
		Map<String, Long> winners = new java.util.HashMap<>();
		for (TournamentMatch m : existing) {
			String key = Bracket.key(m.getRound(), m.getBracketSlot());
			bySlot.put(key, m);
			if (m.isPlayed()) {
				winners.put(key, m.getWinnerEntryId());
			}
		}
		int created = 0;
		for (List<Bracket.Slot> round : Bracket.build(seeds, winners, t.isThirdPlace())) {
			for (Bracket.Slot s : round) {
				if (!s.ready()) {
					continue;
				}
				TournamentMatch m = bySlot.get(Bracket.key(s.round(), s.slot()));
				if (m == null) {
					matches.save(TournamentMatch.knockout(t.getId(), s.round(), s.slot(), s.home(), s.away()));
					created++;
				}
				else if (!m.getHomeEntryId().equals(s.home()) || !m.getAwayEntryId().equals(s.away())) {
					m.replaceParticipants(s.home(), s.away());
					if (m.getStatus() != TournamentMatch.Status.UNSCHEDULED) {
						changed(t, m); // eski takımın planlı maçı iptal, yenisininki açılır
					}
				}
			}
		}
		return created;
	}

	/**
	 * Bu eleme maçının sonucuna bağlı sonraki maç oynandı mı? Galibin oynadığı sonraki tur maçı; yarı finalse
	 * kaybedenin oynadığı üçüncülük maçı da.
	 */
	private boolean nextMatchPlayed(Tournament t, TournamentMatch m) {
		int round = m.getRound() + 1;
		int slot = m.getBracketSlot() / 2;
		int total = Bracket.rounds((int) entries.countByTournamentId(t.getId()));
		boolean semi = round == total && !Bracket.isThirdPlace(m.getRound(), m.getBracketSlot(), total);
		return matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId())
			.stream()
			.filter(x -> x.getRound() == round && x.getBracketSlot() != null && x.isPlayed())
			.anyMatch(x -> x.getBracketSlot() == slot
					|| (semi && t.isThirdPlace() && x.getBracketSlot() == Bracket.THIRD_PLACE_SLOT));
	}

	/** Eleme turnuvasında oynanacak toplam maç: takım − 1 (baylar maç değildir), üçüncülük maçı varsa +1. */
	public static int knockoutMatchCount(Tournament t, int entryCount) {
		if (entryCount < 2) {
			return 0;
		}
		return entryCount - 1 + (t.isThirdPlace() && entryCount >= Bracket.MIN_ENTRIES_THIRD_PLACE ? 1 : 0);
	}

	// ------------------------------------------------------------------ platform takımına bağlama

	/**
	 * Kaptan, personelin paylaştığı bağlantıyla kaydı kendi takımına bağlar. Turnuva satırı kilitlenir; böylece iki
	 * kaptan aynı kaydı aynı anda alamaz. Bağlanınca kaydın planlı ve oynanmış maçları takımın sayfasına yansır.
	 *
	 * @return turnuva
	 */
	@Transactional
	public Long linkTeam(AppUserPrincipal user, String code, Long teamId) {
		Long tournamentId = entries.tournamentIdOfLinkCode(code).orElseThrow(() -> new NotFoundException("Bağlantı"));
		Tournament t = tournaments.findForUpdate(tournamentId).orElseThrow();
		TournamentEntry e = entries.findByLinkCode(code).orElseThrow(() -> new NotFoundException("Bağlantı"));
		if (e.isLinked()) {
			throw new BusinessRuleException("Bu kayıt zaten bir takıma bağlı.");
		}
		if (teamId == null || teamLinks.captainedTeams(user.id()).stream().noneMatch(o -> o.id().equals(teamId))) {
			throw new NotFoundException("Takım");
		}
		e.link(teamId, Instant.now(clock));
		try {
			entries.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu takım bu turnuvada başka bir kayda zaten bağlı.");
		}
		audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_ENTRY_LINKED", "TournamentEntry", e.getId(),
				"team=" + teamId);
		publishEntryMatches(t, e);
		return t.getId();
	}

	/** Personel bağlantıyı kaldırır; kayıt yeni bir kod alır, takımın planlı lig maçları takım sayfasından düşer. */
	@Transactional
	public void unlinkTeam(AppUserPrincipal user, Long tournamentId, Long entryId) {
		Tournament t = lockedForManage(user, tournamentId);
		TournamentEntry e = entries.findById(entryId)
			.filter(x -> x.getTournamentId().equals(tournamentId))
			.orElseThrow(() -> new NotFoundException("Takım"));
		if (!e.isLinked()) {
			return;
		}
		Long old = e.getTeamId();
		e.unlink(TournamentEntry.newLinkCode());
		audit.record(user.id(), t.getBusinessId(), "TOURNAMENT_ENTRY_UNLINKED", "TournamentEntry", e.getId(),
				"team=" + old);
		publishEntryMatches(t, e);
	}

	/**
	 * Yetki ve rıza adımı olmadan bağlar. Yalnızca dev profilindeki demo veri üreticisi kullanır (kullanıcı
	 * isteğinden çağrılmaz).
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public void linkForDemo(Long entryId, Long teamId) {
		TournamentEntry e = entries.findById(entryId).orElseThrow();
		e.link(teamId, Instant.now(clock));
		publishEntryMatches(tournaments.findById(e.getTournamentId()).orElseThrow(), e);
	}

	/** Kaydın planlı ve oynanmış maçları için olay yayınlar (bağlantı değişince takım tarafı kendini günceller). */
	private void publishEntryMatches(Tournament t, TournamentEntry e) {
		for (TournamentMatch m : matches.findByTournamentIdOrderByRoundAscIdAsc(t.getId())) {
			if (m.getStatus() != TournamentMatch.Status.UNSCHEDULED
					&& (m.getHomeEntryId().equals(e.getId()) || m.getAwayEntryId().equals(e.getId()))) {
				changed(t, m);
			}
		}
	}

	/** Maçın güncel hâlini olay olarak yayınlar (bkz. {@link TournamentEvents.MatchChanged}). */
	private void changed(Tournament t, TournamentMatch m) {
		Map<Long, TournamentEntry> es = entries.findByTournamentIdOrderById(t.getId()).stream()
			.collect(Collectors.toMap(TournamentEntry::getId, x -> x));
		String place = null;
		if (m.getPitchId() != null) {
			CatalogService.PitchContext ctx = catalog.pitchContext(m.getPitchId());
			place = ctx.pitch().getName() + " · " + ctx.branch().getName();
		}
		String label = t.isKnockout()
				? Bracket.matchName(m.getRound(), m.getBracketSlot(), es.size() >= 2 ? Bracket.rounds(es.size()) : 1)
				: m.getRound() + ". hafta";
		TournamentEntry h = es.get(m.getHomeEntryId());
		TournamentEntry a = es.get(m.getAwayEntryId());
		events.publishEvent(new TournamentEvents.MatchChanged(m.getId(), t.getId(), t.getName(), label,
				m.getStatus(), m.getStartsAt(), place,
				new TournamentEvents.Side(h.getId(), h.getTeamId(), h.getName(), m.getHomeScore()),
				new TournamentEvents.Side(a.getId(), a.getTeamId(), a.getName(), m.getAwayScore())));
	}

	// ------------------------------------------------------------------ yardımcılar

	Tournament lockedForManage(AppUserPrincipal user, Long tournamentId) {
		Tournament t = tournaments.findForUpdate(tournamentId).orElseThrow(() -> new NotFoundException("Lig"));
		guard.requireBranch(user, t.getBusinessId(), t.getBranchId(), Permission.TOURNAMENT_MANAGE);
		return t;
	}

	private static void requireActive(Tournament t) {
		if (!t.isActive()) {
			throw new BusinessRuleException(t.isDraft() ? "Önce fikstürü oluşturun." : "Lig tamamlandı; değişiklik yapılamaz.");
		}
	}

	private Pitch pitchOf(Tournament t, Long pitchId) {
		if (pitchId == null) {
			throw new BusinessRuleException("Saha seçin.");
		}
		Pitch p = catalog.pitchContext(pitchId).pitch();
		if (!p.getBranchId().equals(t.getBranchId()) || !p.isActive()) {
			throw new NotFoundException("Saha");
		}
		return p;
	}

	private static TimeRange playRange(LocalDateTime start, int minutes, ZoneId zone) {
		if (start == null) {
			throw new BusinessRuleException("Tarih ve saat seçin.");
		}
		if (minutes < MIN_MATCH_MINUTES || minutes > MAX_MATCH_MINUTES || minutes % 15 != 0
				|| start.getMinute() % 15 != 0) {
			throw new BusinessRuleException("Maç süresi 30-180 dakika, başlangıç ve süre 15 dakikanın katı olmalı.");
		}
		Instant s = start.atZone(zone).toInstant();
		return new TimeRange(s, s.plus(Duration.ofMinutes(minutes)));
	}

	private void requireFutureAndOpen(Tournament t, TimeRange play, ZoneId zone) {
		if (!play.start().isAfter(Instant.now(clock))) {
			throw new BusinessRuleException("Geçmiş bir saate maç planlanamaz.");
		}
		LocalDate d = play.start().atZone(zone).toLocalDate();
		BranchSchedule schedule = catalog.schedule(catalog.branchContext(t.getBranchId()).branch(), d.minusDays(1), d);
		if (!schedule.isOpenDuring(play)) {
			throw new BusinessRuleException("Şube bu saatte kapalı.");
		}
	}

	Map<Long, String> entryNames(Long tournamentId) {
		return entries.findByTournamentIdOrderById(tournamentId).stream()
			.collect(Collectors.toMap(TournamentEntry::getId, TournamentEntry::getName));
	}

}
