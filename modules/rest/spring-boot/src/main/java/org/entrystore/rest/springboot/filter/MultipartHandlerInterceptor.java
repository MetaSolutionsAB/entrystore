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
import org.entrystore.rest.springboot.controller.AcceptsMultipart;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Answers 415 to a multipart request unless Spring MVC routed it to a handler marked {@link AcceptsMultipart}.
 * {@code MultipartRequestFilter} allows multipart only on the upload routes, but the handler chosen for such a route
 * also depends on content negotiation: {@code POST /echo} with {@code Accept: application/json} is routed to
 * {@code POST /{context-id}}. Argument resolution there would parse the body before any access check; this runs
 * before it.
 */
@Component
public class MultipartHandlerInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
							 @NotNull Object handler) throws HttpMediaTypeNotSupportedException {
		if (HttpUtil.isMultipart(request) && !(handler instanceof HandlerMethod method
				&& method.hasMethodAnnotation(AcceptsMultipart.class))) {
			throw new HttpMediaTypeNotSupportedException(MediaType.MULTIPART_FORM_DATA, List.of(),
					HttpMethod.valueOf(request.getMethod()));
		}
		return true;
	}
}
