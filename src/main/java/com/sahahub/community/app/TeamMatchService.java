package com.sahahub.community.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamMatch;
import com.sahahub.community.domain.TeamMatchRepository;
import com.sahahub.community.domain.TeamMember;
import com.sahahub.community.domain.TeamMemberRepository;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.notification.app.NotificationWriter;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Takım maçları ve katılım ("geliyorum / kararsızım / gelmiyorum").
 * <ul>
 * <li>Maçı yalnızca kaptan ekler, iptal eder ve skorunu girer. Kaptanın kendi onaylı, ileri tarihli rezervasyonuna
 * bağlanabilir; rezervasyon iptal edilirse takım maçı da iptal olur (aynı transaction).</li>
 * <li>Yanıtı yalnızca takımın aktif üyeleri verir, maç başlayana kadar değiştirebilir. Kimin ne dediğini yalnızca
 * takım üyeleri görür.</li>
 * <li>Maç eklenince/iptal edilince diğer üyelere bildirim gider (tercihlerine göre e-posta/SMS).</li>
 * </ul>
 */
@Service
public class TeamMatchService {

	public static final Duration MAX_AHEAD = Duration.ofDays(90);
	private static final ZoneId TR = ZoneId.of("Europe/Istanbul");
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM EEEE HH:mm",
			Locale.forLanguageTag("tr"));

	public enum Answer {

		GOING("Geliyorum"), MAYBE("Kararsızım"), NOT_GOING("Gelmiyorum");

		private final String label;

		Answer(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

	}

	public record Attendee(String name, Answer answer) {
	}

	public record MatchView(Long id, Long teamId, String teamName, Instant startsAt, String place, String opponent,
			String note, TeamMatch.Status status, Integer ourScore, Integer theirScore, int going, int maybe,
			int notGoing, int noAnswer, Answer myAnswer, boolean open, boolean canScore, List<Attendee> attendees) {
	}

	public record TeamMatches(boolean captain, List<MatchView> upcoming, List<MatchView> history) {
	}

	private final TeamMatchRepository matches;
	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final ReservationRepository reservations;
	private final CatalogService catalog;
	private final AppUserRepository users;
	private final NotificationWriter notifications;
	private final JdbcTemplate jdbc;
	private final Clock clock;

	public TeamMatchService(TeamMatchRepository matches, TeamRepository teams, TeamMemberRepository members,
			ReservationRepository reservations, CatalogService catalog, AppUserRepository users,
			NotificationWriter notifications, JdbcTemplate jdbc, Clock clock) {
		this.matches = matches;
		this.teams = teams;
		this.members = members;
		this.reservations = reservations;
		this.catalog = catalog;
		this.users = users;
		this.notifications = notifications;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ kaptan işlemleri

	@Transactional
	public Long create(AppUserPrincipal user, Long teamId, Long reservationId, LocalDateTime startsAt, String place,
			String opponent, String note) {
		Team team = captainTeam(user, teamId);
		Instant now = Instant.now(clock);
		String opp = clean(opponent, 60, "Rakip adı en fazla 60 karakter.");
		String n = clean(note, 300, "Not en fazla 300 karakter.");
		Instant start;
		String where;
		if (reservationId != null) {
			Reservation r = reservations.findById(reservationId)
				.filter(x -> user.id().equals(x.getCustomerId()))
				.orElseThrow(() -> new NotFoundException("Rezervasyon"));
			if (r.getStatus() != ReservationStatus.CONFIRMED || !r.getStartsAt().isAfter(now)) {
				throw new BusinessRuleException("Yalnızca onaylı ve henüz oynanmamış bir rezervasyon takım maçı yapılabilir.");
			}
			PitchContext ctx = catalog.pitchContext(r.getPitchId());
			start = r.getStartsAt();
			where = ctx.pitch().getName() + " · " + ctx.branch().getName();
		}
		else {
			if (startsAt == null) {
				throw new BusinessRuleException("Maçın tarih ve saatini seçin.");
			}
			start = startsAt.atZone(TR).toInstant();
			if (!start.isAfter(now) || start.isAfter(now.plus(MAX_AHEAD))) {
				throw new BusinessRuleException("Maç zamanı gelecek 90 gün içinde olmalı.");
			}
			where = clean(place, 120, "Yer en fazla 120 karakter.");
			if (where == null) {
				throw new BusinessRuleException("Maçın yerini yazın (ör. tesis adı ve ilçe).");
			}
		}
		TeamMatch m;
		try {
			m = matches.saveAndFlush(new TeamMatch(teamId, reservationId, start, where, opp, n, user.id(), now));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Bu rezervasyon zaten takım maçı olarak eklenmiş.");
		}
		notifyMembers(team, user.id(), "TEAM_MATCH_CREATED", "Yeni takım maçı",
				team.getName() + ": " + start.atZone(TR).format(WHEN) + " · " + where
						+ (opp != null ? " · rakip " + opp : "") + ". Gelip gelmeyeceğinizi bildirin.",
				"team-match:" + m.getId());
		return m.getId();
	}

	@Transactional
	public void cancel(AppUserPrincipal user, Long matchId) {
		TeamMatch m = matches.findById(matchId).orElseThrow(() -> new NotFoundException("Takım maçı"));
		Team team = captainTeam(user, m.getTeamId());
		if (m.getStatus() != TeamMatch.Status.SCHEDULED) {
			throw new BusinessRuleException("Yalnızca planlanmış maç iptal edilir.");
		}
		m.cancel();
		notifyCancelled(team, m, user.id());
	}

	@Transactional
	public void recordScore(AppUserPrincipal user, Long matchId, Integer ours, Integer theirs) {
		TeamMatch m = matches.findById(matchId).orElseThrow(() -> new NotFoundException("Takım maçı"));
		captainTeam(user, m.getTeamId());
		if (ours == null || theirs == null || ours < 0 || theirs < 0 || ours > 99 || theirs > 99) {
			throw new BusinessRuleException("Skor 0-99 arasında iki sayı olmalı.");
		}
		if (m.getStatus() == TeamMatch.Status.CANCELLED) {
			throw new BusinessRuleException("İptal edilmiş maçın skoru girilemez.");
		}
		if (Instant.now(clock).isBefore(m.getStartsAt())) {
			throw new BusinessRuleException("Maç başlamadan skor girilemez.");
		}
		m.recordScore(ours, theirs, Instant.now(clock));
	}

	// ------------------------------------------------------------------ üye işlemleri

	/** Katılım yanıtı verir veya değiştirir (maç başlayana kadar). */
	@Transactional
	public void answer(AppUserPrincipal user, Long matchId, Answer answer) {
		TeamMatch m = matches.findById(matchId).orElseThrow(() -> new NotFoundException("Takım maçı"));
		requireMember(user, m.getTeamId());
		if (answer == null) {
			throw new BusinessRuleException("Bir yanıt seçin.");
		}
		Instant now = Instant.now(clock);
		if (!m.isOpenForAnswers(now)) {
			throw new BusinessRuleException(m.getStatus() == TeamMatch.Status.CANCELLED ? "Bu maç iptal edildi."
					: "Maç başladı; yanıt değiştirilemez.");
		}
		jdbc.update("""
				insert into team_match_attendance (team_match_id, user_id, status, updated_at) values (?, ?, ?, ?)
				on conflict (team_match_id, user_id) do update set status = excluded.status, updated_at = excluded.updated_at""",
				matchId, user.id(), answer.name(), java.sql.Timestamp.from(now));
	}

	/** Bağlı rezervasyon iptal edilince takım maçı da iptal olur ve üyeler haberdar edilir. */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	void onReservationCancelled(ReservationCancelled event) {
		for (TeamMatch m : matches.scheduledForReservation(event.reservationId())) {
			m.cancel();
			teams.findById(m.getTeamId()).ifPresent(t -> notifyCancelled(t, m, null));
		}
	}

	// ------------------------------------------------------------------ görünümler

	/** Takım sayfası: yaklaşan maçlar ve geçmiş (son 20). Yalnızca üyeler. */
	@Transactional(readOnly = true)
	public TeamMatches forTeam(AppUserPrincipal user, Long teamId) {
		TeamMember me = requireMember(user, teamId);
		Instant now = Instant.now(clock);
		List<TeamMatch> up = matches.upcoming(List.of(teamId), now);
		List<TeamMatch> past = matches.history(teamId, now, PageRequest.of(0, 20));
		return new TeamMatches(me.isCaptain(), views(up, user, me.isCaptain(), now, true),
				views(past, user, me.isCaptain(), now, true));
	}

	/** "Takımlarım ve maçlarım": tüm takımlarımın yaklaşan maçları ve yanıtım. */
	@Transactional(readOnly = true)
	public List<MatchView> myUpcoming(AppUserPrincipal user) {
		List<Long> teamIds = teams.activeTeamsOf(user.id()).stream().map(Team::getId).toList();
		if (teamIds.isEmpty()) {
			return List.of();
		}
		Instant now = Instant.now(clock);
		return views(matches.upcoming(teamIds, now), user, false, now, false);
	}

	private List<MatchView> views(List<TeamMatch> list, AppUserPrincipal user, boolean captain, Instant now,
			boolean withNames) {
		if (list.isEmpty()) {
			return List.of();
		}
		Map<Long, Team> teamById = teams.findAllById(list.stream().map(TeamMatch::getTeamId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Team::getId, Function.identity()));
		Map<Long, List<TeamMember>> memberCache = new HashMap<>();
		Map<Long, List<Answered>> answers = answers(list.stream().map(TeamMatch::getId).toList());
		List<MatchView> out = new ArrayList<>();
		for (TeamMatch m : list) {
			List<TeamMember> active = memberCache.computeIfAbsent(m.getTeamId(), members::activeMembers);
			java.util.Set<Long> activeIds = active.stream().map(TeamMember::getUserId).collect(Collectors.toSet());
			List<Answered> mine = answers.getOrDefault(m.getId(), List.of())
				.stream()
				.filter(a -> activeIds.contains(a.userId())) // ayrılan üyelerin yanıtı sayılmaz
				.toList();
			int going = (int) mine.stream().filter(a -> a.answer() == Answer.GOING).count();
			int maybe = (int) mine.stream().filter(a -> a.answer() == Answer.MAYBE).count();
			int no = (int) mine.stream().filter(a -> a.answer() == Answer.NOT_GOING).count();
			Answer my = mine.stream().filter(a -> a.userId().equals(user.id())).map(Answered::answer).findFirst()
				.orElse(null);
			List<Attendee> names = withNames
					? mine.stream().map(a -> new Attendee(a.name(), a.answer())).toList() : List.of();
			out.add(new MatchView(m.getId(), m.getTeamId(), teamById.get(m.getTeamId()).getName(), m.getStartsAt(),
					m.getPlace(), m.getOpponent(), m.getNote(), m.getStatus(), m.getOurScore(), m.getTheirScore(), going,
					maybe, no, active.size() - mine.size(), my, m.isOpenForAnswers(now),
					captain && m.getStatus() != TeamMatch.Status.CANCELLED && !now.isBefore(m.getStartsAt()), names));
		}
		return out;
	}

	private record Answered(Long matchId, Long userId, String name, Answer answer) {
	}

	private Map<Long, List<Answered>> answers(Collection<Long> matchIds) {
		Long[] ids = matchIds.toArray(Long[]::new);
		List<Answered> rows = jdbc.query("""
				select a.team_match_id, a.user_id, u.full_name, a.status from team_match_attendance a
				join app_user u on u.id = a.user_id where a.team_match_id = any(?) order by a.status, u.full_name""",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
				(rs, i) -> new Answered(rs.getLong(1), rs.getLong(2), rs.getString(3), Answer.valueOf(rs.getString(4))));
		return rows.stream().collect(Collectors.groupingBy(Answered::matchId));
	}

	// ------------------------------------------------------------------ yardımcılar

	private Team captainTeam(AppUserPrincipal user, Long teamId) {
		Team t = teams.findById(teamId).filter(Team::isActive).orElseThrow(() -> new NotFoundException("Takım"));
		TeamMember me = members.activeMembership(teamId, user.id()).orElseThrow(() -> new NotFoundException("Takım"));
		if (!me.isCaptain()) {
			throw new BusinessRuleException("Bu işlemi yalnızca takım kaptanı yapabilir.");
		}
		return t;
	}

	private TeamMember requireMember(AppUserPrincipal user, Long teamId) {
		teams.findById(teamId).filter(Team::isActive).orElseThrow(() -> new NotFoundException("Takım"));
		return members.activeMembership(teamId, user.id()).orElseThrow(() -> new NotFoundException("Takım"));
	}

	private void notifyCancelled(Team team, TeamMatch m, Long exceptUserId) {
		notifyMembers(team, exceptUserId, "TEAM_MATCH_CANCELLED", "Takım maçı iptal edildi",
				team.getName() + ": " + m.getStartsAt().atZone(TR).format(WHEN) + " · " + m.getPlace()
						+ " maçı iptal edildi.",
				"team-match-cancelled:" + m.getId());
	}

	private void notifyMembers(Team team, Long exceptUserId, String kind, String title, String body, String dedup) {
		List<Long> ids = members.activeMembers(team.getId()).stream().map(TeamMember::getUserId)
			.filter(id -> !id.equals(exceptUserId))
			.toList();
		for (AppUser u : users.findAllById(ids)) {
			notifications.write(new NotificationWriter.Recipient(u.getId(), u.getEmail(), u.getPhone(),
					u.isNotifyEmail(), u.isNotifySms()), kind, title, body, "/takimlar/" + team.getId(),
					dedup + ":" + u.getId());
		}
	}

	private static String clean(String s, int max, String message) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String v = s.strip();
		if (v.length() > max) {
			throw new BusinessRuleException(message);
		}
		return v;
	}

}
