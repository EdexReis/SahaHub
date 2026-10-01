package com.sahahub.tournament.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BracketTest {

	static List<Long> seeds(int n) {
		return LongStream.rangeClosed(101, 100 + n).boxed().toList(); // 101 = 1. tohum
	}

	@Test
	void standardSeedOrder() {
		assertThat(Bracket.seedOrder(2)).containsExactly(1, 2);
		assertThat(Bracket.seedOrder(4)).containsExactly(1, 4, 2, 3);
		assertThat(Bracket.seedOrder(8)).containsExactly(1, 8, 4, 5, 2, 7, 3, 6);
		assertThat(Bracket.size(5)).isEqualTo(8);
		assertThat(Bracket.rounds(5)).isEqualTo(3);
		assertThat(Bracket.size(16)).isEqualTo(16);
		assertThatThrownBy(() -> Bracket.size(1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void thirdPlaceSlotIsBetweenSemiFinalLosers() {
		List<Long> s = seeds(6); // 8'lik ağaç; yarı finaller 2. turda
		Map<String, Long> w = new HashMap<>();
		w.put(Bracket.key(1, 1), 104L); // 4-5 → 4
		w.put(Bracket.key(1, 3), 106L); // 3-6 → 6
		assertThat(Bracket.build(s, w, true).get(2)).as("yarı finaller oynanmadan").hasSize(2)
			.allSatisfy(x -> assertThat(x.ready()).isFalse());
		w.put(Bracket.key(2, 0), 104L); // 1-4 → 4, kaybeden 1
		w.put(Bracket.key(2, 1), 102L); // 2-6 → 2, kaybeden 6
		List<Bracket.Slot> last = Bracket.build(s, w, true).get(2);
		assertThat(last).hasSize(2);
		assertThat(last.get(0)).isEqualTo(new Bracket.Slot(3, 0, 104L, 102L, false));
		assertThat(last.get(1)).isEqualTo(new Bracket.Slot(3, Bracket.THIRD_PLACE_SLOT, 101L, 106L, false));
		assertThat(Bracket.matchName(3, 1, 3)).isEqualTo("Üçüncülük maçı");
		assertThat(Bracket.matchName(3, 0, 3)).isEqualTo("Final");
		assertThat(Bracket.matchName(2, 1, 3)).as("yarı finalin 1. yeri").isEqualTo("Yarı final");
		// Üç takımda yarı finalde bay olur: üçüncülük yeri eklenmez; kapalıyken de eklenmez
		assertThat(Bracket.build(seeds(3), Map.of(), true).getLast()).hasSize(1);
		assertThat(Bracket.build(s, w, false).getLast()).hasSize(1);
	}

	@Test
	void byesGoToTopSeedsAndNeverMeetEachOther() {
		List<List<Bracket.Slot>> b = Bracket.build(seeds(6), Map.of()); // 8'lik ağaç, 2 bay
		List<Bracket.Slot> r1 = b.getFirst();
		assertThat(r1).hasSize(4);
		assertThat(r1.stream().filter(Bracket.Slot::bye).map(Bracket.Slot::byeWinner)).containsExactlyInAnyOrder(101L, 102L);
		assertThat(r1.stream().filter(Bracket.Slot::ready)).hasSize(2);
		// Bay geçenler ikinci turda yerlerini almış, rakipleri henüz belli değil
		List<Bracket.Slot> r2 = b.get(1);
		assertThat(r2.get(0).home()).isEqualTo(101L);
		assertThat(r2.get(0).away()).isNull();
		// İkinci yarı: 2-7 (bay) ve 3-6 → 2. tohum yerini almış, 3-6 maçının galibi bekleniyor
		assertThat(r2.get(1).home()).isEqualTo(102L);
		assertThat(r2.get(1).away()).isNull();
		assertThat(b.get(2)).singleElement().satisfies(f -> assertThat(f.ready()).isFalse());
	}

	@ParameterizedTest
	@ValueSource(ints = { 2, 3, 4, 5, 7, 8, 9, 12, 16, 20, 32 })
	void everyTeamAppearsOnceInRoundOne_andWinnersFillTheTreeToAChampion(int n) {
		List<Long> s = seeds(n);
		Map<String, Long> winners = new HashMap<>();
		int matches = 0;
		List<List<Bracket.Slot>> b = Bracket.build(s, winners);
		Set<Long> seen = new HashSet<>();
		for (Bracket.Slot slot : b.getFirst()) {
			if (slot.home() != null) {
				assertThat(seen.add(slot.home())).isTrue();
			}
			if (slot.away() != null) {
				assertThat(seen.add(slot.away())).isTrue();
			}
			assertThat(slot.home() == null && slot.away() == null).as("iki bay karşılaşmaz").isFalse();
		}
		assertThat(seen).containsExactlyInAnyOrderElementsOf(s);
		// Her turda hazır maçları "küçük numaralı (daha güçlü tohum) kazanır" diye oynat
		for (int r = 1; r <= Bracket.rounds(n); r++) {
			for (Bracket.Slot slot : Bracket.build(s, winners).get(r - 1)) {
				assertThat(slot.bye() || slot.ready()).as("tur %d yer %d hazır", r, slot.slot()).isTrue();
				if (slot.ready()) {
					winners.put(Bracket.key(r, slot.slot()), Math.min(slot.home(), slot.away()));
					matches++;
				}
			}
		}
		assertThat(matches).as("eleme turnuvasında maç sayısı = takım − 1").isEqualTo(n - 1);
		assertThat(winners.get(Bracket.key(Bracket.rounds(n), 0))).as("1. tohum şampiyon").isEqualTo(101L);
	}

	@Test
	void topTwoSeedsMeetOnlyInTheFinal() {
		List<Long> s = seeds(8);
		Map<String, Long> winners = new HashMap<>();
		for (int r = 1; r <= 3; r++) {
			for (Bracket.Slot slot : Bracket.build(s, winners).get(r - 1)) {
				if (r < 3) {
					assertThat(Set.of(slot.home(), slot.away())).as("tur %d", r).isNotEqualTo(Set.of(101L, 102L));
				}
				winners.put(Bracket.key(r, slot.slot()), Math.min(slot.home(), slot.away()));
			}
		}
		Bracket.Slot fin = Bracket.build(s, winners).get(2).getFirst();
		assertThat(Set.of(fin.home(), fin.away())).isEqualTo(Set.of(101L, 102L));
	}

	@Test
	void roundNames() {
		assertThat(Bracket.roundName(4, 4)).isEqualTo("Final");
		assertThat(Bracket.roundName(3, 4)).isEqualTo("Yarı final");
		assertThat(Bracket.roundName(2, 4)).isEqualTo("Çeyrek final");
		assertThat(Bracket.roundName(1, 4)).isEqualTo("Son 16");
		assertThat(Bracket.roundName(1, 5)).isEqualTo("1. tur");
	}

}
