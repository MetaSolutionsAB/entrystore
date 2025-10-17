package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
			throws ServletException, IOException {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		if (auth != null && auth.isAuthenticated()) {
			if (auth instanceof AnonymousAuthenticationToken) {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			} else if (auth.getPrincipal() instanceof ESUserDetails esUser && esUser.getEsUser() != null) {
				// Cookie has been verified and user is authenticated
				pm.setAuthenticatedUserURI(esUser.getEsUser().getURI());
			} else {
				log.warn("User Authenticated in Spring-boot, but has invalid principal type: {}", auth.getPrincipal());
			}
		}

		filterChain.doFilter(request, response);
	}
}
