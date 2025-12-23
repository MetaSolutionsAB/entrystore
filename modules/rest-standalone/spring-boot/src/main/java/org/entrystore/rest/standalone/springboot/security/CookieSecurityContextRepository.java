package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.CaseUtils;
import org.entrystore.config.Config;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.entrystore.repository.config.Settings.AUTH_COOKIE_HTTPONLY;
import static org.entrystore.repository.config.Settings.AUTH_COOKIE_MAX_AGE;
import static org.entrystore.repository.config.Settings.AUTH_COOKIE_SAMESITE;
import static org.entrystore.repository.config.Settings.AUTH_COOKIE_SECURE;

@Slf4j
@Component
@RequiredArgsConstructor
public class CookieSecurityContextRepository implements SecurityContextRepository {

	private static final int DEFAULT_MAX_AGE_IN_SECONDS = (int) Duration.ofDays(365).toSeconds();
	private static final String COOKIE_NAME = "auth_token";

	private final ESTokenService tokenService;
	private final Config config;

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		Supplier<SecurityContext> supplier = () -> resolveContextFromCookie(request);
		return new SupplierDeferredSecurityContext(supplier);
	}

	private SecurityContext resolveContextFromCookie(HttpServletRequest request) {

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();

		Cookie token = WebUtils.getCookie(request, "auth_token");
		Cookie maxAgeString = WebUtils.getCookie(request, "Max-Age");

		if (maxAgeString != null && Integer.parseInt(maxAgeString.getValue()) < 1) {
			token = null;
		}

		if (token != null) {
			try {
				Authentication authentication = tokenService.getAuthentication(token.getValue());
				if (authentication != null) {
					securityContext.setAuthentication(authentication);
				}
			} catch (Exception e) {
				// token invalid
				log.warn(e.getMessage());
			}
		}
		return securityContext;
	}

	@Override
	public void saveContext(SecurityContext securityContext, HttpServletRequest request, HttpServletResponse response) {
		if (securityContext.getAuthentication() != null && securityContext.getAuthentication().isAuthenticated()) {

			int maxAge = config.getInt(AUTH_COOKIE_MAX_AGE, DEFAULT_MAX_AGE_IN_SECONDS);
			if (request.getParameter("auth_maxage") != null) {
				maxAge = Math.min(maxAge, Integer.parseInt(request.getParameter("auth_maxage")));
			}
			long expiry = Instant.now().getEpochSecond() + maxAge;
			String token = tokenService.generateToken(securityContext.getAuthentication(), expiry);

			boolean httpOnly = "on".equals(config.getString(AUTH_COOKIE_HTTPONLY, "on"));
			boolean secure = "on".equals(config.getString(AUTH_COOKIE_SECURE, "on"));
			String sameSite = CaseUtils.toCamelCase(config.getString(AUTH_COOKIE_SAMESITE, "strict"), true);
			if ("None".equals(sameSite)) {
				secure = true;
			}

			ResponseCookie authCookie = ResponseCookie.from(COOKIE_NAME, token)
					.httpOnly(httpOnly)
					.secure(secure)
					.path("/")
					.maxAge(maxAge)
					.sameSite(sameSite)
					.build();
			response.setHeader(HttpHeaders.SET_COOKIE, authCookie.toString());
		} else {
			if (WebUtils.getCookie(request, COOKIE_NAME) != null) {
				ResponseCookie deleteCookie = ResponseCookie.from(COOKIE_NAME, "")
						.path("/")
						.maxAge(0)
						.build();
				response.setHeader(HttpHeaders.SET_COOKIE, deleteCookie.toString());
			}
		}
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		return WebUtils.getCookie(request, COOKIE_NAME) != null;
	}

	@Override
	@Deprecated
	public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
		return resolveContextFromCookie(requestResponseHolder.getRequest());
	}
}
