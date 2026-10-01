package com.sahahub.booking.domain;

import com.sahahub.shared.domain.BusinessRuleException;

/** Seçilen zaman aralığı sahada başka bir kayıtla (rezervasyon, bakım, hazırlık süresi) çakışıyor. */
public class SlotUnavailableException extends BusinessRuleException {

	public SlotUnavailableException() {
		super("Bu saat artık uygun değil. Lütfen başka bir saat seçin.");
	}

}
