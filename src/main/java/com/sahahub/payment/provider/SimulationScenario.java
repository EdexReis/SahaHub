package com.sahahub.payment.provider;

/** Simülasyon ödeme sayfasında müşterinin (veya testin) seçebileceği sonuçlar. */
public enum SimulationScenario {

	SUCCESS("Ödeme başarılı"),
	FAIL("Ödeme başarısız (ör. yetersiz bakiye)"),
	DELAYED_SUCCESS("Başarılı ama bildirim gecikmeli gelsin"),
	DUPLICATE_SUCCESS("Başarılı ve bildirim iki kez gelsin"),
	SUCCESS_REFUND_FAILS("Başarılı; bu ödemenin iadesi başarısız olsun");

	private final String label;

	SimulationScenario(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
