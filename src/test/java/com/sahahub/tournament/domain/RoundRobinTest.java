package com.sahahub.tournament.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RoundRobinTest {

	static List<Long> teams(int n) {
		return LongStream.rangeClosed(1, n).boxed().toList();
	}

	static String pair(long a, long b) {
		return Math.min(a, b) + "-" + Math.max(a, b);
	}

	@ParameterizedTest
	@ValueSource(ints = { 2, 3, 4, 5, 6, 7, 8, 11, 20 })
	void singleRoundEveryPairMeetsExactlyOnce_andNobodyPlaysTwiceInARound(int n) {
		List<RoundRobin.Pairing> fixture = RoundRobin.generate(teams(n), false);

		assertThat(fixture).hasSize(n * (n - 1) / 2);
		Set<String> pairs = new HashSet<>();
		for (RoundRobin.Pairing p : fixture) {
			assertThat(p.home()).isNotEqualTo(p.away());
			assertThat(pairs.add(pair(p.home(), p.away()))).as("tekrar eden eşleşme %s", p).isTrue();
		}
		int rounds = n % 2 == 0 ? n - 1 : n;
		assertThat(fixture).extracting(RoundRobin.Pairing::round).allMatch(r -> r >= 1 && r <= rounds);
		Map<Integer, Set<Long>> playing = new HashMap<>();
		for (RoundRobin.Pairing p : fixture) {
			Set<Long> s = playing.computeIfAbsent(p.round(), k -> new HashSet<>());
			assertThat(s.add(p.home())).isTrue();
			assertThat(s.add(p.away())).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(ints = { 4, 5, 6 })
	void doubleRoundEachPairPlaysOnceAtEachHome(int n) {
		List<RoundRobin.Pairing> fixture = RoundRobin.generate(teams(n), true);
		assertThat(fixture).hasSize(n * (n - 1));
		Set<String> directed = new HashSet<>();
		for (RoundRobin.Pairing p : fixture) {
			assertThat(directed.add(p.home() + ">" + p.away())).as("aynı ev sahipliği iki kez: %s", p).isTrue();
		}
		int half = n % 2 == 0 ? n - 1 : n;
		assertThat(fixture.stream().mapToInt(RoundRobin.Pairing::round).max().orElseThrow()).isEqualTo(2 * half);
	}

	@Test
	void homeGamesAreRoughlyBalanced() {
		List<RoundRobin.Pairing> fixture = RoundRobin.generate(teams(6), false);
		Map<Long, Integer> home = new HashMap<>();
		fixture.forEach(p -> home.merge(p.home(), 1, Integer::sum));
		// 5 maçın en az 2'si, en fazla 3'ü iç saha
		assertThat(home.values()).allMatch(h -> h >= 2 && h <= 3);
	}

	@Test
	void needsAtLeastTwoTeams() {
		assertThatThrownBy(() -> RoundRobin.generate(List.of(1L), false)).isInstanceOf(IllegalArgumentException.class);
	}

}
