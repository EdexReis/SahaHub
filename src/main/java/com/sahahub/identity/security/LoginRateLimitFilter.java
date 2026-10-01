package com.sahahub.identity.security;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Kilitlenmiş e-posta + IP için giriş isteğini parola kontrolüne bile ulaştırmadan reddeder. */
public class LoginRateLimitFilter extends OncePerRequestFilter {

	private final LoginAttemptService attempts;

	public LoginRateLimitFilter(LoginAttemptService attempts) {
		this.attempts = attempts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("POST".equals(request.getMethod()) && SecurityConfig.LOGIN_PATH.equals(request.getServletPath())
				&& attempts.isLocked(request.getParameter("email"), request.getRemoteAddr())) {
			response.sendRedirect(request.getContextPath() + SecurityConfig.LOGIN_PATH + "?kilitli");
			return;
		}
		chain.doFilter(request, response);
	}

}
