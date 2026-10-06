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

package org.entrystore.rest.springboot.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.entrystore.rest.springboot.model.api.converter.MediaTypeConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

import java.util.Collections;
import java.util.List;

/**
 * Resolves the representation of a GET on an entry URI. Shared by the content negotiation that picks the
 * {@code EntryController} handler and by the RDF handler that serializes, so the two cannot disagree.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EntryMediaTypeResolver {

	private static final String FORMAT_PARAM = "format";

	private static final MediaTypeConverter FORMAT_CONVERTER = new MediaTypeConverter();

	/**
	 * A {@code format} parameter wins over the Accept header, as in 5.x; without it, all Accept headers are resolved
	 * by {@link GraphUtil#resolveEntryMediaType}.
	 *
	 * @throws HttpMediaTypeNotAcceptableException if {@code format} is not a concrete media type, or the Accept
	 *                                             header does not parse or accepts no entry representation
	 */
	public static MediaType resolve(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
		try {
			String formatParam = request.getParameter(FORMAT_PARAM);
			MediaType format = formatParam != null ? FORMAT_CONVERTER.convert(formatParam) : null;
			if (format != null) {
				if (!format.isConcrete()) {
					throw new HttpMediaTypeNotAcceptableException("The format parameter must be a concrete media type");
				}
				return format;
			}
			String accept = String.join(",", Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
			return GraphUtil.resolveEntryMediaType(accept)
					.orElseThrow(() -> new HttpMediaTypeNotAcceptableException(List.of()));
		} catch (InvalidMediaTypeException e) {
			throw new HttpMediaTypeNotAcceptableException("Could not parse the requested media type");
		}
	}
}
