package com.sahahub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.ParseException;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class MoneyInputFormatterTest {

	final MoneyInputFormatter f = new MoneyInputFormatter();
	final Locale tr = Locale.forLanguageTag("tr-TR");

	@Test
	void acceptsTurkishAndDotNotation() throws ParseException {
		assertThat(f.parse("1.400,50", tr)).isEqualByComparingTo("1400.50");
		assertThat(f.parse("1400,5", tr)).isEqualByComparingTo("1400.5");
		assertThat(f.parse("1400.50", tr)).isEqualByComparingTo("1400.50");
		assertThat(f.parse(" 250 ₺", tr)).isEqualByComparingTo("250");
		assertThat(f.parse("1.250.000,00", tr)).isEqualByComparingTo("1250000");
		assertThat(f.parse("", tr)).isNull();
	}

	@Test
	void rejectsGarbage() {
		assertThatThrownBy(() -> f.parse("abc", tr)).isInstanceOf(ParseException.class);
		assertThatThrownBy(() -> f.parse("1,2,3", tr)).isInstanceOf(ParseException.class);
	}

}
