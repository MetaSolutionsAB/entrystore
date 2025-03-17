package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Class reloads User properties on each HTTP request
 */
@Component
@RequiredArgsConstructor
public class DynamicRoleFilter extends OncePerRequestFilter {

	private final UserDetailsService userDetailsService;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof UserDetails userDetails) {
			// Get fresh User details
			UserDetails updatedUser = userDetailsService.loadUserByUsername(userDetails.getUsername());

			if (!updatedUser.isEnabled()) {
				SecurityContextHolder.clearContext();
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "User account is disabled.");
				return;
			}

			UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(updatedUser, updatedUser.getPassword(), updatedUser.getAuthorities());
			SecurityContextHolder.getContext().setAuthentication(newAuth);
		}

		filterChain.doFilter(request, response);
	}
}
