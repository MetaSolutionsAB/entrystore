package org.entrystore.rest.standalone.springboot.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Stores SAML authentication requests in a Caffeine cache keyed by the relay state token,
 * instead of in the HTTP session. This avoids the SameSite=Strict cookie problem where
 * the browser withholds the session cookie on the cross-site POST from the IdP back to
 * the ACS endpoint.
 */
@Component
public class CacheSaml2AuthenticationRequestRepository
		implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

	private final Cache<String, AbstractSaml2AuthenticationRequest> cache =
			Caffeine.newBuilder()
					.expireAfterWrite(2, TimeUnit.MINUTES)
					.build();

	@Override
	public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
		String relayState = request.getParameter("RelayState");
		return (relayState != null) ? cache.getIfPresent(relayState) : null;
	}

	@Override
	public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authRequest,
										  HttpServletRequest request,
										  HttpServletResponse response) {
		if (authRequest != null && authRequest.getRelayState() != null) {
			cache.put(authRequest.getRelayState(), authRequest);
		}
	}

	@Override
	public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(
			HttpServletRequest request, HttpServletResponse response) {
		String relayState = request.getParameter("RelayState");
		if (relayState != null) {
			AbstractSaml2AuthenticationRequest authRequest = cache.getIfPresent(relayState);
			cache.invalidate(relayState);
			return authRequest;
		}
		return null;
	}
}
