package com.sahahub.booking.domain;

import com.sahahub.shared.domain.BusinessRuleException;

public class HoldExpiredException extends BusinessRuleException {

	public HoldExpiredException() {
		super("Saati tutma süreniz doldu ve saat serbest bırakıldı. Lütfen saati yeniden seçin.");
	}

}
