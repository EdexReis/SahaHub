package com.sahahub.shared.domain;

/**
 * İstenen kayıt yok ya da kullanıcının görmesine izin verilmiyor.
 * Başka işletmenin kaydı sorulduğunda da bu kullanılabilir; böylece kaydın var olup
 * olmadığı bilgisi sızdırılmaz.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String what) {
		super(what + " bulunamadı.");
	}

}
