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

package org.entrystore.rest.standalone.springboot.configuration;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.impl.RepositoryManagerImpl;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServerHeaderFilter extends OncePerRequestFilter {

	@Value("${entrystore.http.header.server:}")
	private String configuredServerHeader;
	private String serverHeaderValue;

	@PostConstruct
	public void init() {
		try {
			if (configuredServerHeader == null || configuredServerHeader.isBlank()) {
				serverHeaderValue = "EntryStore/" + RepositoryManagerImpl.getVersion();
			} else {
				serverHeaderValue = configuredServerHeader;
			}
		} catch (Exception e) {
			serverHeaderValue = "EntryStore";
			log.error("Failed to initialize Server header, falling back to '{}': {}",
					serverHeaderValue, e.getMessage(), e);
		}

		log.info("Server response header set to: {}", serverHeaderValue);
	}

	@Override
	protected void doFilterInternal(@NotNull HttpServletRequest request,
									@NotNull HttpServletResponse response,
									@NotNull FilterChain filterChain)
			throws ServletException, IOException {
		response.setHeader("Server", serverHeaderValue);
		filterChain.doFilter(request, response);
	}
}
