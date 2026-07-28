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

/**
 * A custom exception to be used to return 500 (INTERNAL_SERVER_ERROR) by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * There is no dedicated handler for it, so it is handled by {@code AppExceptionHandler.handleGenericException}:
 * - common response model, with "error" set to the bare "Internal Server Error" reason phrase, never the
 *   exception message — the message may therefore name internal details without leaking them to the client
 * - exception message and stack trace logged at error level
 */
public class InternalServerErrorException extends RuntimeException {

	public InternalServerErrorException(String message) {
		super(message);
	}

	public InternalServerErrorException(String message, Throwable cause) {
		super(message, cause);
	}
}
