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

package org.entrystore.rest.springboot.model.exception;

import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return any HTTP Status response and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behavior:
 * - common response model, with "error" field set from the "message" field
 * - the given headers, such as Retry-After, are added to the error response
 * - exception message logged in the logs at info level
 */
@Getter
public class CustomResponseException extends RuntimeException {

	private final HttpStatus status;

	private final HttpHeaders headers;

	public CustomResponseException(String message, HttpStatus status) {
		this(message, status, HttpHeaders.EMPTY, null);
	}

	public CustomResponseException(String message, HttpStatus status, Throwable cause) {
		this(message, status, HttpHeaders.EMPTY, cause);
	}

	public CustomResponseException(String message, HttpStatus status, HttpHeaders headers) {
		this(message, status, headers, null);
	}

	private CustomResponseException(String message, HttpStatus status, HttpHeaders headers, Throwable cause) {
		super(message, cause);
		this.status = status;
		HttpHeaders copy = new HttpHeaders();
		copy.putAll(headers);
		this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
	}
}
