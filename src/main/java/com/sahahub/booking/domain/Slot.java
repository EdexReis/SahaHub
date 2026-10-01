package com.sahahub.booking.domain;

import com.sahahub.shared.domain.TimeRange;

/** Müşteriye gösterilen bir başlangıç saati ve durumu. */
public record Slot(TimeRange play, State state) {

	public enum State {
		AVAILABLE("Uygun"),
		TAKEN("Dolu"),
		BLOCKED("Kapatıldı"),
		PAST("Geçti");

		private final String label;

		State(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public boolean isAvailable() {
		return state == State.AVAILABLE;
	}

}
