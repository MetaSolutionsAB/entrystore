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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.api.ErrorResponse;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.entrystore.rest.springboot.util.SessionIdExceptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * Answers a request that fails with an exception {@link SessionIdExceptions} recognises, so that the exception reaches
 * no framework logger: a WARN line without the exception and a JSON 500, as {@code AppExceptionHandler} answers other
 * unhandled exceptions. {@link EndedSessionFilter} uses it for failures outside Spring MVC, and the resolver from
 * {@link #exceptionResolver()} goes ahead of Spring MVC's own resolvers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EndedSessionResponder {

	private final ErrorResponseWriter errorResponseWriter;

	/** Logs the failure without its exception and answers the request with 500. */
	public void answer(HttpServletRequest request, HttpServletResponse response) throws IOException {
		log.warn("{} {} failed because its session ended or changed during the request", request.getMethod(),
				HttpUtil.sanitizeForLog(request.getRequestURI()));
		errorResponseWriter.writeErrorResponseAsJson(response, ErrorResponse.builder()
				.status(HttpStatus.INTERNAL_SERVER_ERROR.value())
				.path(request.getRequestURI())
				.error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
				.build());
	}

	/**
	 * @return a resolver that answers a request failing with a recognised exception and leaves every other exception
	 * to the next resolver; not a bean, so that it does not compete with Spring MVC's composite resolver
	 */
	public HandlerExceptionResolver exceptionResolver() {
		return (request, response, handler, exception) -> {
			if (!SessionIdExceptions.foundIn(exception)) {
				return null;
			}
			try {
				answer(request, response);
			} catch (IOException e) {
				// the client has gone, so nothing is left to answer
			}
			return new ModelAndView();
		};
	}
}
