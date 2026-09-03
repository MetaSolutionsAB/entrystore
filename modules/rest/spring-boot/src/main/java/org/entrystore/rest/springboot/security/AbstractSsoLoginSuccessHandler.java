/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.springboot.service.auth.BasicVerifier;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;
import java.util.Set;

/**
 * Shared SSO login success flow for CAS, SAML and OIDC: token-type guard, reserved-name check,
 * auto-provisioning, disabled-user check, and the clear-session-then-redirect failure path.
 * Subclasses contribute the protocol-specific parts through the hook methods, optionally
 * carrying per-request state of type {@code C} (resolved once via
 * {@link #resolveContext(HttpServletRequest, Authentication)}) through the flow. The token
 * type {@code T} ties the {@link #tokenType()} guard to the typed token handed to
 * {@code resolveContext} at compile time.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractSsoLoginSuccessHandler<T extends Authentication, C>
		extends SavedRequestAwareAuthenticationSuccessHandler {

	private static final Set<String> RESERVED_USERNAMES = Set.of("admin", "guest");

	protected final ESUserDetailsService userService;
	protected final PrincipalManager principalManager;
	protected final ErrorResponseWriter errorResponseWriter;

	@Override
	public final void onAuthenticationSuccess(HttpServletRequest request,
											  HttpServletResponse response,
											  Authentication authentication) throws IOException, ServletException {
		try {
			handleSsoAuthentication(request, response, authentication);
		} catch (IOException | ServletException e) {
			throw e;
		} catch (Exception e) {
			String user = authentication != null ? authentication.getName() : "<unknown>";
			log.error("Unexpected {} during {} login for user '{}': {}",
					e.getClass().getSimpleName(), authTypeLabel(), HttpUtil.sanitizeForLog(user), e.getMessage(), e);
			// Undo the filter-persisted token before resolving the (subclass-supplied) failure URL: a
			// throwing defaultFailureUrl() must not leave the rejected user authenticated behind the 500.
			HttpUtil.clearAuthenticatedSession(request);
			errorResponseWriter.redirectOrWriteUnauthorized(request, response, defaultFailureUrl(),
					authTypeLabel() + " login failed");
		}
	}

	private void handleSsoAuthentication(HttpServletRequest request,
										 HttpServletResponse response,
										 Authentication authentication) throws IOException, ServletException {

		if (!tokenType().isInstance(authentication)) {
			// Defense in depth: the protocol's filter only ever produces tokens of tokenType(),
			// but if a different token type ever reaches this handler we must not treat
			// that as success — the filter has already persisted it to the SecurityContext.
			// ERROR because reaching this branch indicates a Spring Security wiring bug.
			log.error("Unexpected authentication type '{}' in {} success handler",
					authentication == null ? "null" : authentication.getClass().getName(), authTypeLabel());
			redirectToLoginFailureUrl(request, response, defaultFailureUrl());
			return;
		}

		String username = authentication.getName();
		C context = resolveContext(request, tokenType().cast(authentication));
		log.info("Successfully authenticated via {}, username: '{}'",
				describeAuthSource(context), HttpUtil.sanitizeForLog(username));

		if (isReservedUsername(username)) {
			log.warn("Ignoring reserved username '{}' from {}", HttpUtil.sanitizeForLog(username), describeAuthSource(context));
			redirectToLoginFailureUrl(request, response, resolveFailureUrl(context));
			return;
		}

		User esUser = userService.loadUser(username);
		if (esUser == null) {
			if (!isAutoProvisioningEnabled(context)) {
				// Cause-neutral: the protocol subclass logs the specific reason (e.g. unknown SAML IdP)
				// where it differs, so this shared line must not assert "auto-provisioning is disabled".
				log.warn("Login denied for {} user '{}': not found in EntryStore and not auto-provisioned",
						describeAuthSource(context), HttpUtil.sanitizeForLog(username));
				redirectToLoginFailureUrl(request, response, resolveFailureUrl(context));
				return;
			}
			log.info("User '{}' not found in EntryStore. Creating new user (auto-provisioning enabled)",
					HttpUtil.sanitizeForLog(username));
			esUser = userService.createUser(username);
		} else {
			log.info("Existing EntryStore user '{}' logged in via {}", HttpUtil.sanitizeForLog(username), authTypeLabel());
		}

		if (BasicVerifier.isUserDisabled(principalManager, esUser)) {
			log.warn("Login denied for {} user '{}': account is disabled",
					describeAuthSource(context), HttpUtil.sanitizeForLog(username));
			redirectToLoginFailureUrl(request, response, resolveFailureUrl(context));
			return;
		}

		if (tryCustomSuccessRedirect(request, response, context)) {
			return;
		}
		// Clear any saved request that might point back to SSO endpoints
		new HttpSessionRequestCache().removeRequest(request, response);
		// Proceeds with standard Spring behavior (redirects to defaultTargetUrl)
		super.onAuthenticationSuccess(request, response, authentication);
	}

	private static boolean isReservedUsername(String username) {
		// equalsIgnoreCase (simple per-char case folding) deliberately matches Turkish dotted/dotless-I
		// variants such as "ADMİN" (U+0130) and "admın" (U+0131); a toLowerCase()-based set lookup uses
		// full case mapping and would let them through — and downstream principal lookup lowercases with
		// the default JVM locale, where "ADMİN" can resolve to the real admin principal.
		return RESERVED_USERNAMES.stream().anyMatch(reserved -> reserved.equalsIgnoreCase(username));
	}

	private void redirectToLoginFailureUrl(HttpServletRequest request, HttpServletResponse response,
										   String failureUrl) throws IOException {
		// The protocol's filter already persisted the token to the SecurityContext; undo that
		// before redirecting so the rejected user doesn't remain authenticated.
		HttpUtil.clearAuthenticatedSession(request);
		errorResponseWriter.redirectOrWriteUnauthorized(request, response, failureUrl,
				authTypeLabel() + " login failed");
	}

	@Override
	protected final String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
		// ENTRYSTORE-996: any custom success URL must be resolved and whitelist-validated by the
		// subclass (see tryCustomSuccessRedirect). On fall-through we must use only the trusted default
		// target and never derive it from a request parameter (e.g. ?successurl=) — doing so would be an
		// open redirect, since the SSO callback filters process their callbacks independently.
		// Overriding the two-arg variant intercepts the parameter/referer logic in the Spring base class.
		return getDefaultTargetUrl();
	}

	@Override
	protected final String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
											  Authentication authentication) {
		// Spring's handle() calls this 3-arg variant; its default delegates to the 2-arg determineTargetUrl.
		// Sealing it final stops a subclass from re-opening the ENTRYSTORE-996 open-redirect hole by
		// overriding the variant the framework actually invokes.
		return determineTargetUrl(request, response);
	}

	/** Protocol label used in log lines and the failure message, e.g. {@code "CAS"} or {@code "SAML"}. */
	protected abstract String authTypeLabel();

	/** The only {@link Authentication} type this handler accepts; anything else is rejected. */
	protected abstract Class<T> tokenType();

	/**
	 * Resolves the per-request protocol state carried through the flow. Called once, after the
	 * token-type guard. May return {@code null} if the protocol needs no state.
	 */
	protected abstract C resolveContext(HttpServletRequest request, T token);

	/** Whether a user unknown to EntryStore may be auto-provisioned for this request. */
	protected abstract boolean isAutoProvisioningEnabled(C context);

	/**
	 * The failure redirect URL for paths where no context exists (token-guard and unexpected-exception
	 * paths), or {@code null} to write a 401 JSON error instead.
	 */
	protected abstract String defaultFailureUrl();

	/**
	 * The failure redirect URL for in-flow denials; {@code context} is the non-null value returned by
	 * {@link #resolveContext(HttpServletRequest, Authentication)} (or {@code null} only if that hook
	 * returned {@code null}). Defaults to {@link #defaultFailureUrl()}.
	 */
	protected String resolveFailureUrl(C context) {
		return defaultFailureUrl();
	}

	/** Description of the authentication source for log lines; defaults to {@link #authTypeLabel()}. */
	protected String describeAuthSource(C context) {
		return authTypeLabel();
	}

	/**
	 * Hook for a protocol-specific success redirect (e.g. a whitelist-validated custom URL).
	 * Returns {@code true} if the response was handled; {@code false} to proceed with the
	 * default target redirect.
	 */
	protected boolean tryCustomSuccessRedirect(HttpServletRequest request, HttpServletResponse response,
											   C context) throws IOException {
		return false;
	}
}
