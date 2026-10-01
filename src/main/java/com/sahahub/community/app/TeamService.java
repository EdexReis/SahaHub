package com.sahahub.community.app;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.community.domain.Listing;
import com.sahahub.community.domain.ListingRepository;
import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamMember;
import com.sahahub.community.domain.TeamMemberRepository;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.identity.domain.AppUser;
import com.sahahub.identity.domain.AppUserRepository;
import com.sahahub.identity.security.AppUserPrincipal;
import com.sahahub.shared.domain.BusinessRuleException;
import com.sahahub.shared.domain.NotFoundException;

/**
 * Takım işlemleri. Takım sayfası yalnızca üyelere açıktır; üye olmayan biri takım numarasıyla
 * "bulunamadı" alır (takımın varlığı ve üyelerin adları sızdırılmaz). Katılım yalnızca davet koduyla olur.
 */
@Service
public class TeamService {

	public static final int MAX_TEAMS_PER_USER = 10;

	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	public record TeamCard(Long id, String name, String city, long memberCount, TeamMember.Role myRole) {
	}

	public record MemberRow(Long userId, String name, TeamMember.Role role, Instant joinedAt) {
	}

	public record TeamDetail(Long id, String name, String city, String inviteCode, boolean captain, Long myUserId,
			List<MemberRow> members) {
	}

	public record InvitePreview(String code, String name, String city, long memberCount, boolean alreadyMember,
			boolean full) {
	}

	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final ListingRepository listings;
	private final AppUserRepository users;
	private final Clock clock;

	public TeamService(TeamRepository teams, TeamMemberRepository members, ListingRepository listings,
			AppUserRepository users, Clock clock) {
		this.teams = teams;
		this.members = members;
		this.listings = listings;
		this.users = users;
		this.clock = clock;
	}

	@Transactional
	public Long create(AppUserPrincipal user, String name, String city) {
		String n = name == null ? "" : name.strip();
		String c = city == null ? "" : city.strip();
		if (n.isEmpty() || n.length() > 60) {
			throw new BusinessRuleException("Takım adı 1-60 karakter olmalı.");
		}
		if (c.isEmpty() || c.length() > 60) {
			throw new BusinessRuleException("Şehir seçin.");
		}
		if (teams.activeTeamsOf(user.id()).size() >= MAX_TEAMS_PER_USER) {
			throw new BusinessRuleException("En fazla " + MAX_TEAMS_PER_USER + " takımda olabilirsiniz.");
		}
		Instant now = Instant.now(clock);
		Team t = teams.save(new Team(n, c, newCode(), now));
		members.save(new TeamMember(t.getId(), user.id(), TeamMember.Role.CAPTAIN, now));
		return t.getId();
	}

	@Transactional(readOnly = true)
	public InvitePreview preview(AppUserPrincipal user, String code) {
		Team t = byCode(code);
		long count = members.activeCount(t.getId());
		return new InvitePreview(t.getInviteCode(), t.getName(), t.getCity(), count,
				user != null && members.activeMembership(t.getId(), user.id()).isPresent(), count >= Team.MAX_MEMBERS);
	}

	/** Davet koduyla katılım. Takım satırı kilitlenir: eşzamanlı katılımlarda üye sınırı aşılmaz. */
	@Transactional
	public Long join(AppUserPrincipal user, String code) {
		Team t = teams.findForUpdate(byCode(code).getId()).orElseThrow();
		if (members.activeMembership(t.getId(), user.id()).isPresent()) {
			throw new BusinessRuleException("Zaten bu takımdasınız.");
		}
		if (members.activeCount(t.getId()) >= Team.MAX_MEMBERS) {
			throw new BusinessRuleException("Takım dolu (en fazla " + Team.MAX_MEMBERS + " oyuncu).");
		}
		if (teams.activeTeamsOf(user.id()).size() >= MAX_TEAMS_PER_USER) {
			throw new BusinessRuleException("En fazla " + MAX_TEAMS_PER_USER + " takımda olabilirsiniz.");
		}
		try {
			members.saveAndFlush(new TeamMember(t.getId(), user.id(), TeamMember.Role.MEMBER, Instant.now(clock)));
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessRuleException("Zaten bu takımdasınız.");
		}
		return t.getId();
	}

	/**
	 * Takımdan ayrılma. Kaptan, başka üye varken ayrılamaz (önce kaptanlığı devreder); tek üye kaptansa
	 * takım dağılır.
	 *
	 * @return takım dağıldıysa true
	 */
	@Transactional
	public boolean leave(AppUserPrincipal user, Long teamId) {
		Team t = lockedMemberTeam(user, teamId);
		TeamMember me = members.activeMembership(teamId, user.id()).orElseThrow();
		Instant now = Instant.now(clock);
		if (me.isCaptain()) {
			if (members.activeCount(teamId) > 1) {
				throw new BusinessRuleException("Kaptan takımdan ayrılmadan önce kaptanlığı başka bir oyuncuya devretmeli.");
			}
			disbandLocked(t, now);
			return true;
		}
		me.leave(now);
		return false;
	}

	@Transactional
	public void removeMember(AppUserPrincipal user, Long teamId, Long memberUserId) {
		lockedCaptainTeam(user, teamId);
		if (memberUserId.equals(user.id())) {
			throw new BusinessRuleException("Kendinizi çıkaramazsınız; takımdan ayrılın veya kaptanlığı devredin.");
		}
		members.activeMembership(teamId, memberUserId)
			.orElseThrow(() -> new NotFoundException("Oyuncu"))
			.leave(Instant.now(clock));
	}

	@Transactional
	public void transferCaptaincy(AppUserPrincipal user, Long teamId, Long newCaptainUserId) {
		lockedCaptainTeam(user, teamId);
		TeamMember next = members.activeMembership(teamId, newCaptainUserId)
			.orElseThrow(() -> new NotFoundException("Oyuncu"));
		if (next.isCaptain()) {
			return;
		}
		TeamMember me = members.activeMembership(teamId, user.id()).orElseThrow();
		me.demoteToMember();
		members.flush(); // tek kaptan indeksi: önce eski kaptan düşer, sonra yenisi atanır
		next.promoteToCaptain();
	}

	@Transactional
	public void regenerateInvite(AppUserPrincipal user, Long teamId) {
		lockedCaptainTeam(user, teamId).changeInviteCode(newCode());
	}

	@Transactional
	public void disband(AppUserPrincipal user, Long teamId) {
		disbandLocked(lockedCaptainTeam(user, teamId), Instant.now(clock));
	}

	@Transactional(readOnly = true)
	public List<TeamCard> myTeams(AppUserPrincipal user) {
		return teams.activeTeamsOf(user.id()).stream().map(t -> {
			TeamMember me = members.activeMembership(t.getId(), user.id()).orElseThrow();
			return new TeamCard(t.getId(), t.getName(), t.getCity(), members.activeCount(t.getId()), me.getRole());
		}).toList();
	}

	@Transactional(readOnly = true)
	public List<Team> captainOf(AppUserPrincipal user) {
		return teams.captainedBy(user.id());
	}

	@Transactional(readOnly = true)
	public TeamDetail detail(AppUserPrincipal user, Long teamId) {
		Team t = teams.findById(teamId).filter(Team::isActive).orElseThrow(() -> new NotFoundException("Takım"));
		TeamMember me = members.activeMembership(teamId, user.id()).orElseThrow(() -> new NotFoundException("Takım"));
		List<TeamMember> list = members.activeMembers(teamId);
		Map<Long, AppUser> people = users.findAllById(list.stream().map(TeamMember::getUserId).toList())
			.stream()
			.collect(Collectors.toMap(AppUser::getId, Function.identity()));
		return new TeamDetail(t.getId(), t.getName(), t.getCity(), me.isCaptain() ? t.getInviteCode() : null,
				me.isCaptain(), user.id(),
				list.stream()
					.map(m -> new MemberRow(m.getUserId(), people.get(m.getUserId()).getFullName(), m.getRole(),
							m.getJoinedAt()))
					.toList());
	}

	// ------------------------------------------------------------------ yardımcılar

	private void disbandLocked(Team t, Instant now) {
		members.activeMembers(t.getId()).forEach(m -> m.leave(now));
		for (Listing l : listings.openForTeam(t.getId())) {
			l.close(now);
		}
		t.disband(now);
	}

	private Team byCode(String code) {
		return teams.findByInviteCode(code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT))
			.filter(Team::isActive)
			.orElseThrow(() -> new NotFoundException("Davet bağlantısı"));
	}

	private Team lockedMemberTeam(AppUserPrincipal user, Long teamId) {
		Team t = teams.findForUpdate(teamId).filter(Team::isActive).orElseThrow(() -> new NotFoundException("Takım"));
		members.activeMembership(teamId, user.id()).orElseThrow(() -> new NotFoundException("Takım"));
		return t;
	}

	private Team lockedCaptainTeam(AppUserPrincipal user, Long teamId) {
		Team t = lockedMemberTeam(user, teamId);
		if (!members.activeMembership(teamId, user.id()).orElseThrow().isCaptain()) {
			throw new BusinessRuleException("Bu işlemi yalnızca takım kaptanı yapabilir.");
		}
		return t;
	}

	private static String newCode() {
		StringBuilder sb = new StringBuilder(10);
		for (int i = 0; i < 10; i++) {
			sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
		}
		return sb.toString();
	}

}
