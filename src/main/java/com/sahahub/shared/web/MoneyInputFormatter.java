package com.sahahub.shared.web;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Locale;

import org.springframework.format.Formatter;

/**
 * Formlardan gelen tutarları okur. Türkçe kullanıcı "1.400,50" yazar; tarayıcı veya önceden doldurulmuş
 * alan "1400.50" gönderebilir. İkisi de kabul edilir:
 * <ul>
 * <li>Virgül varsa ondalık ayırıcıdır; noktalar binlik ayırıcı sayılıp atılır.</li>
 * <li>Virgül yoksa nokta ondalık ayırıcıdır.</li>
 * <li>Boşluk ve ₺ işareti yok sayılır.</li>
 * </ul>
 */
public class MoneyInputFormatter implements Formatter<BigDecimal> {

	@Override
	public BigDecimal parse(String text, Locale locale) throws ParseException {
		String s = text.replace("₺", "").replace("TL", "").replace(" ", "").replace(" ", "").strip();
		if (s.isEmpty()) {
			return null;
		}
		if (s.contains(",")) {
			s = s.replace(".", "").replace(',', '.');
		}
		if (!s.matches("-?\\d+(\\.\\d+)?")) {
			throw new ParseException(text, 0);
		}
		return new BigDecimal(s);
	}

	@Override
	public String print(BigDecimal value, Locale locale) {
		return value.toPlainString();
	}

}
