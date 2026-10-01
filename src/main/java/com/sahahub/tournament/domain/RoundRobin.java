package com.sahahub.tournament.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Lig fikstürü (round-robin, "daire yöntemi").
 * <p>
 * n takım için (n tekse bir "bay" eklenir) n-1 hafta üretilir; her hafta her takım en fazla bir maç yapar,
 * her ikili tam bir kez karşılaşır. İlk takım sabit kalır, diğerleri her hafta bir adım döner. Ev sahipliği
 * haftadan haftaya değiştirilerek dengelenir. Çift devrede ikinci yarı, ilk yarının ev/deplasman ters
 * çevrilmiş tekrarıdır. Bay'a düşen takım o hafta maç yapmaz.
 * <p>
 * Saf fonksiyon: veritabanı ve Spring'e bağlı değildir (RoundRobinTest).
 */
public final class RoundRobin {

	public record Pairing(int round, long home, long away) {
	}

	private RoundRobin() {
	}

	public static List<Pairing> generate(List<Long> entryIds, boolean doubleRound) {
		if (entryIds.size() < 2) {
			throw new IllegalArgumentException("En az iki takım gerekli");
		}
		List<Long> teams = new ArrayList<>(entryIds);
		if (teams.size() % 2 == 1) {
			teams.add(null); // bay
		}
		int n = teams.size();
		int rounds = n - 1;
		List<Pairing> first = new ArrayList<>();
		for (int r = 0; r < rounds; r++) {
			for (int i = 0; i < n / 2; i++) {
				Long a = teams.get(i);
				Long b = teams.get(n - 1 - i);
				if (a == null || b == null) {
					continue;
				}
				// Sabit takımın (i == 0) ev sahipliği haftalara göre değişir; diğer eşleşmelerde de çift/tek dengesi
				boolean swap = (i == 0) ? r % 2 == 1 : i % 2 == 1;
				first.add(swap ? new Pairing(r + 1, b, a) : new Pairing(r + 1, a, b));
			}
			// Döndür: ilk eleman sabit, son eleman ikinci sıraya geçer
			teams.add(1, teams.remove(n - 1));
		}
		if (!doubleRound) {
			return first;
		}
		List<Pairing> all = new ArrayList<>(first);
		for (Pairing p : first) {
			all.add(new Pairing(p.round() + rounds, p.away(), p.home()));
		}
		return all;
	}

}
