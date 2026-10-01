package com.sahahub.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sahahub.support.IntegrationTest;
import com.sahahub.support.TestData;

/**
 * Regresyon: hibernate.jdbc.time_zone=UTC ayarı varken 00:00 değeri veritabanına 22:00 olarak
 * yazılıyordu (1970 tarihli İstanbul farkı). Çalışma saatlerinin olduğu gibi saklandığını doğrular.
 */
@IntegrationTest
class LocalTimePersistenceIT {

	@Autowired
	TestData data;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void openingHoursAreStoredExactly() {
		var v = data.venue(); // 09:00 - 01:00
		var row = jdbc.queryForMap("select open_time, close_time from branch_opening_hours where branch_id = ? "
				+ "and day_of_week = 1", v.branch().getId());
		assertThat(row.get("open_time").toString()).isEqualTo(LocalTime.of(9, 0) + ":00");
		assertThat(row.get("close_time").toString()).isEqualTo(LocalTime.of(1, 0) + ":00");
	}

	@Test
	void instantsAreStoredInUtc() {
		var v = data.venue();
		String stored = jdbc.queryForObject(
				"select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI') from business where id = ?",
				String.class, v.business().getId());
		assertThat(stored).isEqualTo("2026-01-01 00:00");
	}

}
