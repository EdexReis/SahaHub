package com.sahahub.shared.domain;

/**
 * Bir iş kuralı ihlal edildiğinde fırlatılır. Mesaj doğrudan kullanıcıya gösterilecek
 * Türkçe metindir; bu yüzden teknik ayrıntı (SQL, sınıf adı vb.) içermemelidir.
 */
public class BusinessRuleException extends RuntimeException {

	public BusinessRuleException(String userMessage) {
		super(userMessage);
	}

}
