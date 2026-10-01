package com.sahahub.tournament.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sahahub.tournament.domain.Standings.Result;
import com.sahahub.tournament.domain.Standings.Row;

class StandingsTest {

	static final Standings.Points P = new Standings.Points(3, 1, 0);

	static Map<Long, String> teams() {
		Map<Long, String> m = new LinkedHashMap<>();
		m.put(1L, "Ankara");
		m.put(2L, "Bursa");
		m.put(3L, "Ceyhan");
		m.put(4L, "Düzce");
		return m;
	}

	@Test
	void pointsGoalsAndOrder() {
		List<Row> rows = Standings.compute(teams(), List.of(new Result(1, 2, 3, 1), new Result(3, 4, 2, 2),
				new Result(2, 3, 0, 1), new Result(4, 1, 1, 1)), P);

		Row first = rows.getFirst();
		assertThat(first.name()).isEqualTo("Ankara");
		assertThat(first.points()).isEqualTo(4); // G + B
		assertThat(first.played()).isEqualTo(2);
		assertThat(first.goalsFor()).isEqualTo(4);
		assertThat(first.goalsAgainst()).isEqualTo(2);
		assertThat(rows).extracting(Row::name).containsExactly("Ankara", "Ceyhan", "Düzce", "Bursa");
		assertThat(rows).extracting(Row::position).containsExactly(1, 2, 3, 4);
		Row bursa = rows.getLast();
		assertThat(bursa.lost()).isEqualTo(2);
		assertThat(bursa.points()).isZero();
	}

	@Test
	void tieBreakIsGoalDifferenceThenGoalsForThenName() {
		// Ankara ve Bursa 3'er puan; Ankara averajda önde
		List<Row> a = Standings.compute(teams(), List.of(new Result(1, 3, 4, 0), new Result(2, 4, 1, 0)), P);
		assertThat(a).extracting(Row::name).startsWith("Ankara", "Bursa");

		// Averaj eşit (+1), Bursa daha çok gol atmış
		List<Row> b = Standings.compute(teams(), List.of(new Result(1, 3, 1, 0), new Result(2, 4, 3, 2)), P);
		assertThat(b).extracting(Row::name).startsWith("Bursa", "Ankara");

		// Hiç maç yok: hepsi eşit, ada göre
		assertThat(Standings.compute(teams(), List.of(), P)).extracting(Row::name)
			.containsExactly("Ankara", "Bursa", "Ceyhan", "Düzce");
	}

	@Test
	void customPointsAreUsed() {
		List<Row> rows = Standings.compute(teams(), List.of(new Result(1, 2, 0, 0), new Result(3, 4, 1, 0)),
				new Standings.Points(2, 1, 0));
		assertThat(rows.getFirst().name()).isEqualTo("Ceyhan");
		assertThat(rows.getFirst().points()).isEqualTo(2);
	}

}
