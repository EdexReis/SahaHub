package com.sahahub.tournament.app;

import java.time.Instant;

import com.sahahub.tournament.domain.TournamentMatch;

/** Turnuva modülünün yayınladığı olaylar. Aynı transaction içinde yayınlanır. */
public final class TournamentEvents {

	private TournamentEvents() {
	}

	/** Maçın bir tarafı. teamId: kayıt platformdaki bir takıma bağlıysa o takım, değilse null. */
	public record Side(Long entryId, Long teamId, String name, Integer score) {
	}

	/**
	 * Maçın planı, sonucu ya da tarafları değişti (ya da tarafın takım bağlantısı değişti). Olay maçın o anki
	 * tamamını taşır; dinleyen, kendi kopyasını buna göre günceller.
	 *
	 * @param label tur adı (ör. "2. hafta", "Yarı final")
	 * @param place planlıysa "Saha · Şube", değilse null
	 */
	public record MatchChanged(Long matchId, Long tournamentId, String tournamentName, String label,
			TournamentMatch.Status status, Instant startsAt, String place, Side home, Side away) {
	}

}
