package com.sahahub.shared.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Türkçe Excel ile açılabilen CSV: ayraç noktalı virgül, ondalık virgül, UTF-8 BOM.
 * <p>
 * <b>Formül enjeksiyonu koruması</b>: Kullanıcının yazdığı bir metin (takım adı, müşteri adı) "=", "+", "-",
 * "@", sekme veya satır başı ile başlıyorsa Excel onu formül olarak çalıştırabilir (ör.
 * {@code =HYPERLINK(...)}). Böyle hücrelerin başına tek tırnak eklenir ve metin olarak kalır. Sayılar ayrı
 * yöntemle yazılır; eksi işaretli tutarlar bozulmaz.
 */
public final class CsvWriter {

	private static final char SEP = ';';

	private final StringBuilder out = new StringBuilder("﻿");
	private final List<String> row = new ArrayList<>();

	public CsvWriter text(String value) {
		row.add(quote(guard(value == null ? "" : value)));
		return this;
	}

	public CsvWriter number(BigDecimal value) {
		row.add(value == null ? "" : value.toPlainString().replace('.', ','));
		return this;
	}

	public CsvWriter number(long value) {
		row.add(Long.toString(value));
		return this;
	}

	public CsvWriter endRow() {
		out.append(String.join(String.valueOf(SEP), row)).append("\r\n");
		row.clear();
		return this;
	}

	public CsvWriter header(String... names) {
		for (String n : names) {
			text(n);
		}
		return endRow();
	}

	@Override
	public String toString() {
		return out.toString();
	}

	static String guard(String s) {
		if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
			return "'" + s;
		}
		return s;
	}

	private static String quote(String s) {
		if (s.indexOf(SEP) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
			return '"' + s.replace("\"", "\"\"") + '"';
		}
		return s;
	}

}
