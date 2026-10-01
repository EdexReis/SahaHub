package com.sahahub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CsvWriterTest {

	@Test
	void formulaLikeTextIsNeutralised_numbersAreNot() {
		String csv = new CsvWriter().text("=HYPERLINK(\"http://kotu.example\")")
			.text("+90 555")
			.text("-1")
			.text("@SUM(A1)")
			.text("Normal ad")
			.number(new BigDecimal("-50.25"))
			.number(1200)
			.endRow()
			.toString();
		assertThat(csv).startsWith("﻿");
		String row = csv.substring(1).strip();
		assertThat(row.split(";")).containsExactly("\"'=HYPERLINK(\"\"http://kotu.example\"\")\"", "'+90 555", "'-1",
				"'@SUM(A1)", "Normal ad", "-50,25", "1200");
	}

	@Test
	void separatorsAndNewlinesAreQuoted() {
		String csv = new CsvWriter().text("A;B").text("satır\nsonu").endRow().toString();
		assertThat(csv).contains("\"A;B\";\"satır\nsonu\"\r\n");
	}

}
