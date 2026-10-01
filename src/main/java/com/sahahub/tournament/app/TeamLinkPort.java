package com.sahahub.tournament.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Turnuva modülünün platform takımları hakkında bilmesi gerekenler. Uygulaması topluluk modülündedir; böylece
 * bağımlılık tek yönlü kalır (topluluk → turnuva).
 */
public interface TeamLinkPort {

	record TeamOption(Long id, String name) {
	}

	/** Kullanıcının kaptanı olduğu, dağılmamış takımlar. */
	List<TeamOption> captainedTeams(Long userId);

	/** Dağılmamış takımların adları (dağılmış ya da olmayan takım haritada yer almaz). */
	Map<Long, String> activeTeamNames(Collection<Long> teamIds);

}
