package org.entrystore.rest.springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.model.auth.SessionInfo;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Class reloads User properties on each HTTP request
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReloadUserPropertiesFilter extends OncePerRequestFilter {

	private final ESUserDetailsService userDetailsService;
	private final SessionRegistry sessionRegistry;

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
		throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof ESUserSessionDetails esUserDetails) {
			try {
				// Get fresh User details
				ESUserSessionDetails updatedUser = (ESUserSessionDetails) userDetailsService.loadUserByUsername(esUserDetails.getUsername());
				Instant now = Instant.now();
				SessionInfo.SessionInfoBuilder sessionInfo = SessionInfo.builder()
						.userName(esUserDetails.getSessionInfo().userName())
						.loginTime(esUserDetails.getSessionInfo().loginTime())
						.loginExpiration(LocalDateTime.ofInstant(now.plusSeconds(request.getSession().getMaxInactiveInterval()), ZoneId.systemDefault()))
						.lastAccessTime(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
						.lastUsedIpAddress(request.getRemoteAddr())
						.lastUsedUserAgent(request.getHeader("User-Agent"))
						.loginTokenMaxAge(request.getSession().getMaxInactiveInterval());

				if (!updatedUser.isEnabled()) {
					SecurityContextHolder.clearContext();
					HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
							.status(HttpStatus.FORBIDDEN.value())
							.path(request.getRequestURI())
							.error("User account is disabled.")
							.build());
					return;
				}

				updatedUser.setSessionInfo(sessionInfo.build());

				UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(updatedUser, updatedUser.getPassword(), updatedUser.getAuthorities());
				SecurityContextHolder.getContext().setAuthentication(newAuth);
				sessionRegistry.registerNewSession(request.getSession().getId(), updatedUser);
			} catch (UsernameNotFoundException e) {
				SecurityContextHolder.clearContext();
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.UNAUTHORIZED.value())
						.path(request.getRequestURI())
						.error("User account is not found.")
						.build());
				return;
			} catch (ClassCastException e) {
				log.error("Unexpected principal type during user details reload", e);
				SecurityContextHolder.clearContext();
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			} catch (Exception e) {
				log.error("Failed to reload user details", e);
				SecurityContextHolder.clearContext();
				HttpUtil.writeErrorResponseAsJson(response, ErrorResponse.builder()
						.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
						.path(request.getRequestURI())
						.error("Authentication error.")
						.build());
				return;
			}
		}

		filterChain.doFilter(request, response);
	}
}
