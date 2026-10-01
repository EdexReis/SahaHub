package com.sahahub.booking.domain;

/** Rezervasyonun geldiği kanal. */
public enum Channel {

	ONLINE("Çevrim içi"),
	PHONE("Telefon"),
	WALK_IN("Yüz yüze");

	private final String label;

	Channel(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
