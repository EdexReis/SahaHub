package com.sahahub.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Rezervasyon yaşam döngüsü. Ödeme durumu bundan ayrıdır (ödeme modülünde tutulacak):
 * "Onaylandı" bir rezervasyonun kaporası henüz ödenmemiş olabilir.
 *
 * <pre>
 *   HELD ──► CONFIRMED ──► COMPLETED
 *    │  │        │  └────► NO_SHOW
 *    │  │        └───────► CANCELLED
 *    │  └────────────────► CANCELLED
 *    └───────────────────► EXPIRED
 * </pre>
 */
public enum ReservationStatus {

	HELD("Geçici tutuluyor"),
	CONFIRMED("Onaylandı"),
	CANCELLED("İptal edildi"),
	COMPLETED("Tamamlandı"),
	NO_SHOW("Gelmedi"),
	EXPIRED("Süresi doldu");

	private final String label;

	ReservationStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public Set<ReservationStatus> allowedNext() {
		return switch (this) {
			case HELD -> EnumSet.of(CONFIRMED, CANCELLED, EXPIRED);
			case CONFIRMED -> EnumSet.of(COMPLETED, NO_SHOW, CANCELLED);
			case CANCELLED, COMPLETED, NO_SHOW, EXPIRED -> EnumSet.noneOf(ReservationStatus.class);
		};
	}

	public boolean canTransitionTo(ReservationStatus next) {
		return allowedNext().contains(next);
	}

	/**
	 * Bu durumdaki rezervasyon sahayı meşgul eder mi? İptal edilen ve süresi dolan
	 * kayıtlar doluluk yaratmaz; tamamlanan/gelmeyen kayıtlar geçmiş zamanı tutmaya devam eder.
	 */
	public boolean occupiesPitch() {
		return this != CANCELLED && this != EXPIRED;
	}

	/** Henüz sonuçlanmamış, değiştirilebilir rezervasyon mu? */
	public boolean isOpen() {
		return this == HELD || this == CONFIRMED;
	}

}
