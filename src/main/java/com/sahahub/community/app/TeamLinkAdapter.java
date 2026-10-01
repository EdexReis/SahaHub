package com.sahahub.community.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sahahub.community.domain.Team;
import com.sahahub.community.domain.TeamRepository;
import com.sahahub.tournament.app.TeamLinkPort;

/** Turnuva modülünün platform takımlarına bakışı ({@link TeamLinkPort}). */
@Component
class TeamLinkAdapter implements TeamLinkPort {

	private final TeamRepository teams;

	TeamLinkAdapter(TeamRepository teams) {
		this.teams = teams;
	}

	@Override
	@Transactional(readOnly = true)
	public List<TeamOption> captainedTeams(Long userId) {
		return teams.captainedBy(userId).stream().map(t -> new TeamOption(t.getId(), t.getName())).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, String> activeTeamNames(Collection<Long> teamIds) {
		if (teamIds.isEmpty()) {
			return Map.of();
		}
		return teams.findAllById(teamIds).stream().filter(Team::isActive)
			.collect(Collectors.toMap(Team::getId, Team::getName));
	}

}
