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
import org.entrystore.rest.springboot.util.EntryMediaTypeResolver;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Selects the representation of a GET or HEAD on an entry URI with {@link EntryMediaTypeResolver}, as 5.x did: the
 * {@code format} parameter wins over the Accept header, and a wildcard gets {@code application/rdf+xml}. The
 * selected type picks the {@code EntryController} handler, JSON or RDF. Every other request is negotiated by the
 * delegate on the Accept header alone; endpoints that honour {@code format} read it themselves.
 */
@RequiredArgsConstructor
public class EntryEndpointContentNegotiationStrategy implements ContentNegotiationStrategy {

	// Regex to match URLs like /abc123/entry/xyz456
	private static final Pattern ENTRY_URL_PATTERN = Pattern.compile("^/[^/]+/entry/[^/]+$");

	// HEAD is served by the GET handlers, so it must pick the same one.
	private static final Set<String> READ_METHODS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name());

	private final ContentNegotiationStrategy delegate;

	@Override
	public @NonNull List<MediaType> resolveMediaTypes(NativeWebRequest webRequest) throws HttpMediaTypeNotAcceptableException {
		HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
		// Servlet path excludes the context path (server.servlet.context-path), which getRequestURI() includes.
		if (servletRequest != null && READ_METHODS.contains(servletRequest.getMethod())
				&& ENTRY_URL_PATTERN.matcher(servletRequest.getServletPath()).matches()) {
			return List.of(EntryMediaTypeResolver.resolve(servletRequest));
		}
		return delegate.resolveMediaTypes(webRequest);
	}
}
