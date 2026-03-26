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
 * A custom exception to be used in a service layer to indicate that Controller layer should
 * return 409 (CLIENT_ERROR_CONFLICT). This unifies the behaviour in ControllerAdvice (ExceptionHandler) across all services.
 * Expected behaviour:
 * - common response model, with "error" set as exception message
 * - exception message logged in the logs at DEBUG level
 */
public class DataConflictException extends RuntimeException {

	public DataConflictException(String message) {
		super(message);
	}

	public DataConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
