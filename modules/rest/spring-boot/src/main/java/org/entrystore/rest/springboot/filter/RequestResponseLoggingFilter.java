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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.http.HttpException;
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.util.HttpQueryRedactor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs each HTTP request in one INFO line once its response is complete, as the 5.x access log did:
 * {@code GET /store/1/entry/2?includeAll 200 16ms client=192.0.2.1}. The query is redacted by
 * {@link HttpQueryRedactor}.
 * <p>
 * An asynchronous response, such as a streamed SPARQL result, is logged when it completes, with its final
 * status and duration. A request whose handler throws is logged with the status Jetty answers it with: the
 * code of an {@link HttpException} or {@link UnavailableException} in its cause chain, else 500, or the
 * status already sent if the response was committed. The start of each request is logged at DEBUG, to trace requests that never complete.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanConfig(value = "logging.http.enabled", matchIfMissing = true)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain)
			throws ServletException, IOException {
		String target = target(request);
		String client = request.getRemoteAddr();
		log.debug("{} started client={}", target, client);
		long start = System.nanoTime();

		try {
			filterChain.doFilter(request, response);
		} catch (Throwable e) {
			logCompleted(target, response.isCommitted() ? response.getStatus() : statusFor(e), start, client);
			throw e;
		}
		if (request.isAsyncStarted()) {
			request.getAsyncContext().addListener(new CompletionLogger(response, target, client, start));
		} else {
			logCompleted(target, response.getStatus(), start, client);
		}
	}

	// Skip logging for favicon requests.
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getServletPath();
		return path.equals("/favicon.ico");
	}

	private static String target(HttpServletRequest request) {
		String query = request.getQueryString();
		String target = request.getMethod() + " " + request.getRequestURI();
		return query == null || query.isEmpty() ? target : target + "?" + HttpQueryRedactor.redact(query);
	}

	/** The status Jetty sends for an exception that leaves the filter chain. */
	static int statusFor(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpException http) {
				return http.getCode();
			}
			if (cause instanceof UnavailableException unavailable) {
				return unavailable.isPermanent() ? 404 : 503;
			}
		}
		return 500;
	}

	private static void logCompleted(String target, int status, long startNanos, String client) {
		log.info("{} {} {}ms client={}", target, status, (System.nanoTime() - startNanos) / 1_000_000, client);
	}

	/** Logs an asynchronous request when it completes; onComplete also follows a timeout or an error. */
	private record CompletionLogger(HttpServletResponse response, String target, String client, long startNanos)
			implements AsyncListener {

		@Override
		public void onComplete(AsyncEvent event) {
			logCompleted(target, response.getStatus(), startNanos, client);
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			// A new async cycle drops the registered listeners, so stay registered for its completion.
			event.getAsyncContext().addListener(this);
		}

		@Override
		public void onTimeout(AsyncEvent event) {
		}

		@Override
		public void onError(AsyncEvent event) {
		}
	}
}
