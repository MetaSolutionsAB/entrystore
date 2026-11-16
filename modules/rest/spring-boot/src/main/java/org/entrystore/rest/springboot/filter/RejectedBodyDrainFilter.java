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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the unread rest of the request body, within the limits of {@link HttpUtil#discardRejectedBody}, after an
 * error response. Jetty closes a connection whose request body has not fully arrived when the response completes,
 * so a reverse proxy that sends the whole body before it reads the response, such as Apache mod_proxy, gets a broken
 * pipe and answers 502 instead of the error. Runs first, so it also covers the errors written by the other filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RejectedBodyDrainFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain) throws ServletException, IOException {
		filterChain.doFilter(request, response);
		if (response.getStatus() >= 400 && !request.isAsyncStarted()) {
			HttpUtil.discardRejectedBody(request);
		}
	}
}
