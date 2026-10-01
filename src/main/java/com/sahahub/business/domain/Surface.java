package com.sahahub.business.domain;

public enum Surface {

	ARTIFICIAL_TURF("Suni çim"),
	NATURAL_GRASS("Doğal çim"),
	PARQUET("Parke"),
	RUBBER("Kauçuk");

	private final String label;

	Surface(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
