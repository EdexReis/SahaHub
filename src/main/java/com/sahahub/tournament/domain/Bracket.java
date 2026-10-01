package com.sahahub.tournament.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Eleme usulü turnuva ağacı. Saf hesap: tohum sırası (eklenme sırası, 1 = en güçlü) ve oynanmış maçların
 * galipleri verilir, her turun her yerinin (slot) kimlerin arasında olduğu döner.
 * <ul>
 * <li>Ağaç boyu, takım sayısından büyük-eşit en küçük 2'nin kuvvetidir (ör. 6 takım → 8).</li>
 * <li>Standart tohumlama: 8'lik ağaçta yerleşim 1-8, 4-5, 2-7, 3-6. Böylece 1. ve 2. tohum ancak finalde,
 * ilk dört tohum ancak yarı finalde karşılaşır.</li>
 * <li>Eksik takım yerleri "bay"dır ve en üst tohumlara düşer; bay geçen takım maç yapmadan ikinci tura
 * çıkar. Takım sayısı ağaç boyunun yarısından büyük olduğu için iki bay karşılaşmaz.</li>
 * <li>Sonraki turun k. yeri, önceki turun 2k ve 2k+1. yerlerinin galipleri arasındadır.</li>
 * </ul>
 * Testler: BracketTest.
 */
public final class Bracket {

	public static final int MAX_ENTRIES = 32;

	/** Ağaçta bir yer. home/away null ise o taraf henüz belli değil. bye: tek takım var, maç yapılmaz. */
	public record Slot(int round, int slot, Long home, Long away, boolean bye) {

		public boolean ready() {
			return !bye && home != null && away != null;
		}

		/** Bay geçen takım (yalnızca bye ise). */
		public Long byeWinner() {
			return bye ? (home != null ? home : away) : null;
		}

	}

	private Bracket() {
	}

	public static int size(int entries) {
		if (entries < 2) {
			throw new IllegalArgumentException("En az iki takım gerekli");
		}
		int s = 1;
		while (s < entries) {
			s <<= 1;
		}
		return s;
	}

	public static int rounds(int entries) {
		return Integer.numberOfTrailingZeros(size(entries));
	}

	/** Standart tohum yerleşimi (1'den başlayan tohum numaraları, ağaç sırasıyla). */
	public static List<Integer> seedOrder(int size) {
		List<Integer> order = new ArrayList<>(List.of(1, 2));
		while (order.size() < size) {
			int n = order.size() * 2;
			List<Integer> next = new ArrayList<>();
			for (int s : order) {
				next.add(s);
				next.add(n + 1 - s);
			}
			order = next;
		}
		return order.subList(0, size);
	}

	/**
	 * @param seeds takımlar tohum sırasıyla
	 * @param winners oynanmış maçların galipleri: anahtar {@link #key(int, int)}
	 * @return turlar (1'den başlar), her turun yerleri
	 */
	public static List<List<Slot>> build(List<Long> seeds, Map<String, Long> winners) {
		int size = size(seeds.size());
		List<Integer> order = seedOrder(size);
		List<List<Slot>> rounds = new ArrayList<>();
		List<Slot> first = new ArrayList<>();
		for (int k = 0; k < size / 2; k++) {
			Long a = entry(seeds, order.get(2 * k));
			Long b = entry(seeds, order.get(2 * k + 1));
			first.add(new Slot(1, k, a, b, a == null || b == null));
		}
		rounds.add(first);
		for (int r = 2; r <= rounds(seeds.size()); r++) {
			List<Slot> prev = rounds.get(r - 2);
			List<Slot> cur = new ArrayList<>();
			for (int k = 0; k < prev.size() / 2; k++) {
				cur.add(new Slot(r, k, advancing(prev.get(2 * k), winners), advancing(prev.get(2 * k + 1), winners),
						false));
			}
			rounds.add(cur);
		}
		return rounds;
	}

	/** Bir yerden üst tura çıkan takım: bay ise o takım, maç oynandıysa galip, yoksa henüz belli değil. */
	static Long advancing(Slot s, Map<String, Long> winners) {
		if (s.bye()) {
			return s.byeWinner();
		}
		return winners.get(key(s.round(), s.slot()));
	}

	public static String key(int round, int slot) {
		return round + ":" + slot;
	}

	/** Turun adı: son tur "Final", ondan önceki "Yarı final" … */
	public static String roundName(int round, int totalRounds) {
		return switch (totalRounds - round) {
			case 0 -> "Final";
			case 1 -> "Yarı final";
			case 2 -> "Çeyrek final";
			case 3 -> "Son 16";
			default -> round + ". tur";
		};
	}

	private static Long entry(List<Long> seeds, int seed) {
		return seed <= seeds.size() ? seeds.get(seed - 1) : null;
	}

}
