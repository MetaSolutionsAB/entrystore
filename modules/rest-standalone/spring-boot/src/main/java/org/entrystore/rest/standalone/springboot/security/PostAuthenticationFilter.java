package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Class sets the user URI in PrincipalManager after successful authentication
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostAuthenticationFilter extends OncePerRequestFilter {

	private final PrincipalManager pm;
	private final ESUserDetailsService userDetailsService;

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		if (auth != null && auth.isAuthenticated()) {
			if (auth instanceof AnonymousAuthenticationToken) {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			} else if (auth instanceof Saml2Authentication) {
				String username = auth.getName();
				User user = userDetailsService.loadUser(username);
				if (user != null) {
					pm.setAuthenticatedUserURI(user.getURI());
				} else {
					log.warn("Authenticated SAML user '{}' not found in EntryStore, denying access", username);
					throw new ForbiddenException("Authenticated SAML user '" + username + "' not found in EntryStore");
				}
			} else if (auth.getPrincipal() instanceof ESUserSessionDetails esUser && esUser.getEsUser() != null) {
				// Cookie has been verified and user is authenticated
				pm.setAuthenticatedUserURI(esUser.getEsUser().getURI());
			} else {
				log.warn("User Authenticated in Spring-boot, but has invalid principal type: {}", auth.getPrincipal());
			}
		}

		filterChain.doFilter(request, response);
	}
}
