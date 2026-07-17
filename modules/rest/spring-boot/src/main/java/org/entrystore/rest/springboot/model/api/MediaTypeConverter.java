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

package org.entrystore.rest.springboot.model.api;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Binds media-type request parameters (e.g. {@code format}, {@code rdfFormat}) to {@link MediaType}.
 * Media types should be sent properly encoded — i.e. '+' as %2B — but the non-encoded form is also
 * supported: the servlet layer decodes an unencoded '+' to a space, which is restored on a retry when
 * the value does not parse as-is. Parse-first keeps properly encoded values (e.g. a %20-encoded space
 * before a parameter) intact.
 *
 * <p>Blank input converts to {@code null} (parameter treated as absent), matching the convention of
 * Spring's built-in String converters. Unparseable input throws {@link InvalidMediaTypeException} and
 * is deliberately not translated to an application exception: {@code TypeConverterDelegate} swallows
 * any exception thrown by a {@code Converter} and falls back to spring-web's {@code MediaTypeEditor},
 * so the client-facing 400 message is crafted in {@code AppExceptionHandler} from the resulting
 * {@code MethodArgumentTypeMismatchException} instead.
 */
@Component
public class MediaTypeConverter implements Converter<String, MediaType> {

	@Override
	public MediaType convert(@NonNull String input) {
		if (input.isBlank()) {
			return null;
		}
		String trimmed = input.trim();
		try {
			return MediaType.parseMediaType(trimmed);
		} catch (InvalidMediaTypeException e) {
			// an unencoded '+' arrives as a space after servlet decoding; retry with it restored
			return MediaType.parseMediaType(trimmed.replace(' ', '+'));
		}
	}
}
