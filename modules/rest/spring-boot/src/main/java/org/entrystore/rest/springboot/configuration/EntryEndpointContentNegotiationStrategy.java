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
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * This class overrides the default response media type to "application/rdf+xml", when no Accept header is defined
 * Applies only for the entry endpoint. Other endpoints default reply format is set to JSON by MvcConfiguration.
 * The value produced by this Strategy is used to match with the correct endpoint in the controller.
 */
@RequiredArgsConstructor
public class EntryEndpointContentNegotiationStrategy implements ContentNegotiationStrategy {

	// Regex to match URLs like /abc123/entry/xyz456
	private static final Pattern ENTRY_URL_PATTERN = Pattern.compile("^/[^/]+/entry/[^/]+$");

	private final ContentNegotiationStrategy springBootDefaultStrategy;

	@Override
	public @NonNull List<MediaType> resolveMediaTypes(NativeWebRequest webRequest) throws HttpMediaTypeNotAcceptableException {
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		// Servlet path excludes the context path (server.servlet.context-path), which getRequestURI() includes.
		if (servletRequest != null && HttpMethod.GET.name().equals(servletRequest.getMethod()) && ENTRY_URL_PATTERN.matcher(servletRequest.getServletPath()).matches()) {
			String accept = servletRequest.getHeader("Accept");
			if (StringUtils.isEmpty(accept) || MediaType.ALL_VALUE.equals(accept)) {
				return List.of(MediaType.valueOf(GraphUtil.DEFAULT_RDF_MEDIA_TYPE));
			}
		}

		// fallback to default strategy
		return springBootDefaultStrategy.resolveMediaTypes(webRequest);
	}
}
