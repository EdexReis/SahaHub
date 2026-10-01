package com.sahahub.shared.web;

import jakarta.servlet.http.HttpServletRequest;

public final class Htmx {

	private Htmx() {
	}

	/** İstek HTMX tarafından mı gönderildi (tam sayfa yerine sayfa parçası mı bekleniyor)? */
	public static boolean isHtmx(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request"));
	}

}
