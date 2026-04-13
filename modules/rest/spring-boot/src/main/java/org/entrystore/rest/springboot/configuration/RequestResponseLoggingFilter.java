package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OncePerRequest Filter that logs the incoming HTTP requests and outgoing HTTP responses.
 * <p>
 * It captures the request/response bodies, truncates them based on configuration, and logs the execution time.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "logging.http.enabled", havingValue = "true", matchIfMissing = true)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain)
			throws ServletException, IOException {

		log.info("REQUEST  {} {} | query={} | client={}",
				request.getMethod(), request.getRequestURI(),
				request.getQueryString(), request.getRemoteAddr());

		long start = System.currentTimeMillis();

		// Below line executes the rest of the flow here - so don't touch!
		filterChain.doFilter(request, response);

		long duration = System.currentTimeMillis() - start;

		log.info("RESPONSE {} {} | status={} | duration={}ms",
				request.getMethod(), request.getRequestURI(),
				response.getStatus(), duration);
	}

	/**
	 * Skip logging for /management/* management endpoints
	 *
	 * @param request request
	 * @return whether request should be logged
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/management");
	}
}
