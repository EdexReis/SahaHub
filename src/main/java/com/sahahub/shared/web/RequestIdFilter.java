package com.sahahub.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Her isteğe bir takip kimliği verir. Loglarda [requestId] olarak görünür,
 * X-Request-Id başlığıyla yanıta eklenir ve hata sayfasında gösterilir.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,40}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		// Dışarıdan gelen kimliği yalnızca güvenli biçimdeyse kabul et (log enjeksiyonunu önler)
		String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
				? incoming
				: UUID.randomUUID().toString().substring(0, 13);
		MDC.put(MDC_KEY, requestId);
		request.setAttribute(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

}
