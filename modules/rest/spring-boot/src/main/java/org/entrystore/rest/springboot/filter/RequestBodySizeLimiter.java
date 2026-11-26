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
import org.entrystore.rest.springboot.configuration.MaxResourceSize;
import org.entrystore.rest.springboot.controller.MaxRequestBodySize;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Arrays;

/**
 * Limits the size of a body that a handler binds through a message converter ({@code @RequestBody},
 * {@code HttpEntity}) to {@code entrystore.data.max-resource-size}, or to the handler's {@link MaxRequestBodySize}.
 * Such a body is read whole during argument resolution, so before the handler method runs, and with it its access
 * checks and {@code @PreAuthorize}. A larger body is answered 413.
 *
 * <p>As interceptor, it rejects a Content-Length above the limit before the body is opened: Spring opens it before
 * any {@code RequestBodyAdvice} runs, which makes Jetty answer {@code Expect: 100-continue}. As advice, it reads at
 * most one byte past the limit, so a body without Content-Length, or with one that understates it, is limited too.
 * The interceptor must be registered in {@code MvcConfiguration}; the advice is found as {@code @ControllerAdvice}.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class RequestBodySizeLimiter extends RequestBodyAdviceAdapter implements HandlerInterceptor {

	private static final String SUBJECT = "Request body";

	private final MaxResourceSize maxResourceSize;

	@Override
	public boolean preHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
							 @NotNull Object handler) {
		if (handler instanceof HandlerMethod method && bindsBody(method)) {
			HttpUtil.rejectContentLengthAbove(request.getContentLengthLong(),
					limitFor(method.getMethodAnnotation(MaxRequestBodySize.class)), SUBJECT);
		}
		return true;
	}

	@Override
	public boolean supports(@NotNull MethodParameter methodParameter, @NotNull Type targetType,
							@NotNull Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@NotNull
	@Override
	public HttpInputMessage beforeBodyRead(@NotNull HttpInputMessage inputMessage, @NotNull MethodParameter parameter,
										   @NotNull Type targetType,
										   @NotNull Class<? extends HttpMessageConverter<?>> converterType)
			throws IOException {
		long limit = limitFor(parameter.getMethodAnnotation(MaxRequestBodySize.class));
		if (limit == -1) {
			return inputMessage;
		}
		return new ReadBody(inputMessage.getHeaders(), HttpUtil.readAtMost(inputMessage.getBody(), limit, SUBJECT));
	}

	private static boolean bindsBody(HandlerMethod method) {
		return Arrays.stream(method.getMethodParameters()).anyMatch(parameter ->
				parameter.hasParameterAnnotation(RequestBody.class)
						|| HttpEntity.class.isAssignableFrom(parameter.getParameterType()));
	}

	/** The limit in bytes, or -1 for none. */
	private long limitFor(MaxRequestBodySize annotation) {
		return (annotation != null) ? annotation.value() : maxResourceSize.getLimit();
	}

	/** A body read by {@link #beforeBodyRead}, for the converter to parse. */
	private record ReadBody(HttpHeaders headers, byte[] bytes) implements HttpInputMessage {

		@NotNull
		@Override
		public InputStream getBody() {
			return new ByteArrayInputStream(bytes);
		}

		@NotNull
		@Override
		public HttpHeaders getHeaders() {
			return headers;
		}
	}
}
