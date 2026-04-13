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

package org.entrystore.rest.springboot.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default {@code BasicErrorController} so that errors that fall through
 * the {@link AppExceptionHandler} (most importantly: requests to URIs that don't match any
 * controller) return the same {@link ErrorResponse} JSON shape as every other handled error,
 * with a helpful 404 message identifying this as the EntryStore REST API.
 */
@RestController
@RequestMapping("/error")
public class CustomErrorController implements ErrorController {

	static final String NO_RESOURCE_FOUND_MESSAGE =
			"You made a request against the EntryStore REST API. There is no resource at this URI.";

	@RequestMapping
	public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
		HttpStatus status = resolveStatus(request);
		String path = resolveOriginalPath(request);
		String error = (status == HttpStatus.NOT_FOUND)
				? NO_RESOURCE_FOUND_MESSAGE
				: status.getReasonPhrase();

		ErrorResponse body = ErrorResponse.builder()
				.status(status.value())
				.path(path)
				.error(error)
				.build();

		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

	private static HttpStatus resolveStatus(HttpServletRequest request) {
		Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (statusAttr instanceof Integer code) {
			try {
				return HttpStatus.valueOf(code);
			} catch (IllegalArgumentException ignored) {
				// fall through to default
			}
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	private static String resolveOriginalPath(HttpServletRequest request) {
		Object pathAttr = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		return (pathAttr != null) ? pathAttr.toString() : request.getRequestURI();
	}
}
