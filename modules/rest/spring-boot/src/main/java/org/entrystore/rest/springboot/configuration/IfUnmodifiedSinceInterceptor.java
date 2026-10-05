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

package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.service.ContextService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers 412 Precondition Failed to a write on an entry, its metadata or its resource when
 * {@code If-Unmodified-Since} is older than what is written, as 5.x did. entrystore.js sends the entry's
 * modification date on every save, and EntryScape shows its conflict dialog only on 412.
 *
 * <p>As in 5.x, a header within one second of the entry's modification date passes, since HTTP dates carry no
 * milliseconds; otherwise it fails if it is older than the written representation's date at second precision,
 * which is the cache date for cached external metadata and the entry's modification date elsewhere. The caller
 * must first be allowed to read what the route writes (the resource for {@code /resource/}, otherwise the
 * metadata), so a 412 reveals nothing a GET would not. A missing entry, an absent or unparsable header, and safe
 * methods are left to the controller.
 */
@Component
@RequiredArgsConstructor
public class IfUnmodifiedSinceInterceptor implements HandlerInterceptor {

	static final List<String> PATH_PATTERNS = List.of(
			"/*/entry/*", "/*/resource/*", "/*/metadata/*", "/*/cached-external-metadata/*");

	private static final Set<String> WRITE_METHODS = Set.of("PUT", "POST", "DELETE");

	private final ContextService contextService;

	private final PrincipalManager principalManager;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!WRITE_METHODS.contains(request.getMethod())) {
			return true;
		}
		long ifUnmodifiedSince;
		try {
			ifUnmodifiedSince = request.getDateHeader(HttpHeaders.IF_UNMODIFIED_SINCE);
		} catch (IllegalArgumentException e) {
			return true;
		}
		if (ifUnmodifiedSince < 0) {
			return true;
		}

		@SuppressWarnings("unchecked")
		Map<String, String> pathVariables =
				(Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		if (pathVariables == null) {
			return true;
		}
		Context context = contextService.getContext(pathVariables.get("context-id"));
		Entry entry = context != null ? context.get(pathVariables.get("entry-id")) : null;
		Date modified = entry != null ? entry.getModifiedDate() : null;
		if (modified == null) {
			return true;
		}

		String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		boolean resourceRoute = pattern != null && pattern.contains("/resource/");
		principalManager.checkAuthenticatedUserAuthorized(entry,
				resourceRoute ? AccessProperty.ReadResource : AccessProperty.ReadMetadata);

		if (Math.abs(modified.getTime() - ifUnmodifiedSince) < 1000) {
			return true;
		}
		Date representationDate = "cached-external-metadata".equals(pathVariables.get("type"))
				? entry.getExternalMetadataCacheDate()
				: modified;
		if (representationDate != null && ifUnmodifiedSince < representationDate.getTime() / 1000 * 1000) {
			throw new CustomResponseException("The entry has been modified since the If-Unmodified-Since date",
					HttpStatus.PRECONDITION_FAILED);
		}
		return true;
	}
}
