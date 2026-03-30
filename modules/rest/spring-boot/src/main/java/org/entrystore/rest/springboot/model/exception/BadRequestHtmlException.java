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

import org.springframework.http.HttpStatus;

/**
 * A custom exception to be used to return HTML 400 (BAD_REQUEST) response by the service and unify the behavior in ControllerAdvice (ExceptionHandler).
 * The behaviour:
 * - common response model, with "error" field set as exception message
 * - exception message logged in the logs at debug level
 */
public class BadRequestHtmlException extends HtmlResponseException {

	public BadRequestHtmlException(String message, String title) {
		super(message, title, HttpStatus.BAD_REQUEST);
	}

	public BadRequestHtmlException(String message, String title, String linkUrl) {
		super(message, title, HttpStatus.BAD_REQUEST, linkUrl);
	}
}
