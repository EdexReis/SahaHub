package com.sahahub.tournament.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puan durumu. Saklanmaz, her seferinde oynanmış maçlardan hesaplanır (skor düzeltmesi kendiliğinden yansır).
 * <p>
 * Sıralama: puan → averaj (atılan − yenilen) → atılan gol → takım adı (alfabetik, Türkçe sıralama değil;
 * eşitlik durumunda deterministik olsun diye). İkili averaj gibi kurallar uygulanmaz.
 */
public final class Standings {

	public record Points(int win, int draw, int loss) {
	}

	/** Hesaplamaya giren oynanmış maç (yalnızca skorlu maçlar verilir). */
	public record Result(long home, long away, int homeScore, int awayScore) {
	}

	public record Row(int position, long entryId, String name, int played, int won, int drawn, int lost,
			int goalsFor, int goalsAgainst, int points) {

		public int goalDifference() {
			return goalsFor - goalsAgainst;
		}

	}

	private Standings() {
	}

	public static List<Row> compute(Map<Long, String> entries, List<Result> results, Points pts) {
		Map<Long, int[]> t = new LinkedHashMap<>(); // played, won, drawn, lost, gf, ga, points
		entries.keySet().forEach(id -> t.put(id, new int[7]));
		for (Result r : results) {
			int[] h = t.get(r.home());
			int[] a = t.get(r.away());
			if (h == null || a == null) {
				throw new IllegalArgumentException("Maç ligde olmayan takım içeriyor");
			}
			h[0]++;
			a[0]++;
			h[4] += r.homeScore();
			h[5] += r.awayScore();
			a[4] += r.awayScore();
			a[5] += r.homeScore();
			if (r.homeScore() > r.awayScore()) {
				h[1]++;
				a[3]++;
				h[6] += pts.win();
				a[6] += pts.loss();
			}
			else if (r.homeScore() < r.awayScore()) {
				a[1]++;
				h[3]++;
				a[6] += pts.win();
				h[6] += pts.loss();
			}
			else {
				h[2]++;
				a[2]++;
				h[6] += pts.draw();
				a[6] += pts.draw();
			}
		}
		List<Row> rows = new ArrayList<>();
		t.forEach((id, v) -> rows.add(new Row(0, id, entries.get(id), v[0], v[1], v[2], v[3], v[4], v[5], v[6])));
		rows.sort(Comparator.comparingInt(Row::points)
			.thenComparingInt(Row::goalDifference)
			.thenComparingInt(Row::goalsFor)
			.reversed()
			.thenComparing(Row::name));
		List<Row> ranked = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			ranked.add(new Row(i + 1, r.entryId(), r.name(), r.played(), r.won(), r.drawn(), r.lost(), r.goalsFor(),
					r.goalsAgainst(), r.points()));
		}
		return ranked;
	}

}
