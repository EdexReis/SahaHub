package com.sahahub.identity.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Giriş sonucunu hız sınırı sayacına bildiren işleyiciler. */
final class LoginHandlers {

	private LoginHandlers() {
	}

	static class Success extends SavedRequestAwareAuthenticationSuccessHandler {

		private final LoginAttemptService attempts;

		Success(LoginAttemptService attempts) {
			this.attempts = attempts;
			setDefaultTargetUrl("/");
		}

		@Override
		public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
				Authentication authentication) throws IOException, ServletException {
			attempts.recordSuccess(request.getParameter("email"), request.getRemoteAddr());
			super.onAuthenticationSuccess(request, response, authentication);
		}

	}

	static class Failure extends SimpleUrlAuthenticationFailureHandler {

		private final LoginAttemptService attempts;

		Failure(LoginAttemptService attempts) {
			super(SecurityConfig.LOGIN_PATH + "?hata");
			this.attempts = attempts;
		}

		@Override
		public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException exception) throws IOException, ServletException {
			attempts.recordFailure(request.getParameter("email"), request.getRemoteAddr());
			super.onAuthenticationFailure(request, response, exception);
		}

	}

}
