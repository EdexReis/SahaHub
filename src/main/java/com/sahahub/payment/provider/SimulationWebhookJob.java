package com.sahahub.payment.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gecikmeli simülasyon bildirimlerini zamanı gelince teslim eder. Testlerde kapalıdır. */
@Component
@ConditionalOnProperty(name = "sahahub.payment.simulation.dispatch-enabled", havingValue = "true", matchIfMissing = true)
class SimulationWebhookJob {

	private final SimulationWebhookDispatcher dispatcher;

	SimulationWebhookJob(SimulationWebhookDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${sahahub.payment.simulation.dispatch-interval:PT5S}")
	void run() {
		dispatcher.deliverDue();
	}

}
