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

package org.entrystore.rest.springboot.configuration;

import org.eclipse.jetty.ee11.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee11.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.StringUtil;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.time.LocalDateTime;

/**
 * Replaces Jetty's built-in error handlers with sanitized variants.
 * <p>
 * Errors that escape Spring MVC — exceptions thrown in servlet filters (e.g. Jetty's
 * "Unable to parse form content" raised by {@code getParameter()} on a request with a malformed
 * {@code Content-Type} header), or requests Jetty rejects before they reach the servlet context —
 * are rendered by Jetty's default {@link ErrorHandler}, not by
 * {@code AppExceptionHandler} ({@code ErrorMvcAutoConfiguration} is excluded, so there is no
 * {@code /error} dispatch fallback either). Jetty's default output leaks the raw connector-level
 * request URL (internal host and port as seen behind a reverse proxy), the servlet name
 * ("origin") and the internal exception message.
 * <p>
 * The sanitized handlers emit only the HTTP status, its reason phrase, the request path and a
 * timestamp — mirroring the {@code ErrorResponse} envelope used by {@code AppExceptionHandler}.
 * <p>
 * Two levels need replacing:
 * <ul>
 * <li>the servlet context's error handler, which Spring Boot installs as a {@link WebAppContext}
 * {@code Configuration} at context start — appended configurations run after Boot's, so the
 * replacement happens in one of those;</li>
 * <li>the server-level error handler, which renders failures that never reach the servlet
 * context (malformed request line or headers).</li>
 * </ul>
 */
@Component
public class JettyErrorHandlerCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {

	@Override
	public void customize(JettyServletWebServerFactory factory) {
		factory.addConfigurations(new AbstractConfiguration(new AbstractConfiguration.Builder()) {
			@Override
			public void configure(WebAppContext context) {
				context.setErrorHandler(new SanitizedServletErrorHandler());
			}
		});
		factory.addServerCustomizers(server -> server.setErrorHandler(new SanitizedServerErrorHandler()));
	}

	private static void harden(ErrorHandler handler) {
		handler.setShowStacks(false);
		handler.setShowCauses(false);
		handler.setShowOrigin(false);
		handler.setShowMessageInTitle(false);
	}

	/**
	 * Handles errors raised within the servlet context (servlet filters included). Extends
	 * {@link ErrorPageErrorHandler} so servlet error-page dispatch would still work if error
	 * pages were ever registered.
	 */
	static class SanitizedServletErrorHandler extends ErrorPageErrorHandler {

		SanitizedServletErrorHandler() {
			harden(this);
		}

		// Match Spring Boot's JettyEmbeddedErrorHandler: error bodies for all HTTP methods,
		// not only the GET/POST/HEAD default of the core handler.
		@Override
		public boolean errorPageForMethod(String method) {
			return true;
		}

		@Override
		protected void writeErrorHtml(Request request, Writer writer, Charset charset, int code, String message,
				Throwable cause) throws IOException {
			SanitizedErrorWriter.writeHtml(writer, code);
		}

		@Override
		protected void writeErrorPlain(Request request, PrintWriter writer, int code, String message,
				Throwable cause) {
			SanitizedErrorWriter.writePlain(request, writer, code);
		}

		@Override
		protected void writeErrorJson(Request request, PrintWriter writer, int code, String message,
				Throwable cause) {
			SanitizedErrorWriter.writeJson(request, writer, code);
		}
	}

	/**
	 * Handles errors raised outside the servlet context, e.g. requests Jetty rejects while
	 * parsing the request line or headers.
	 */
	static class SanitizedServerErrorHandler extends ErrorHandler {

		SanitizedServerErrorHandler() {
			harden(this);
		}

		@Override
		protected void writeErrorHtml(Request request, Writer writer, Charset charset, int code, String message,
				Throwable cause) throws IOException {
			SanitizedErrorWriter.writeHtml(writer, code);
		}

		@Override
		protected void writeErrorPlain(Request request, PrintWriter writer, int code, String message,
				Throwable cause) {
			SanitizedErrorWriter.writePlain(request, writer, code);
		}

		@Override
		protected void writeErrorJson(Request request, PrintWriter writer, int code, String message,
				Throwable cause) {
			SanitizedErrorWriter.writeJson(request, writer, code);
		}
	}

	/**
	 * Emits the sanitized error body: status, reason phrase, request path (never scheme, host or
	 * query) and a timestamp. Field names mirror
	 * {@code org.entrystore.rest.springboot.model.api.ErrorResponse}.
	 */
	static final class SanitizedErrorWriter {

		private SanitizedErrorWriter() {
		}

		static void writeJson(Request request, PrintWriter writer, int code) {
			StringBuilder json = new StringBuilder(128);
			json.append("{\"timestamp\":").append(quote(LocalDateTime.now().toString()));
			json.append(",\"status\":").append(code);
			String path = path(request);
			if (path != null) {
				json.append(",\"path\":").append(quote(path));
			}
			json.append(",\"error\":").append(quote(reason(code)));
			json.append('}');
			writer.append(json);
		}

		static void writePlain(Request request, PrintWriter writer, int code) {
			writer.printf("HTTP ERROR %d %s%n", code, reason(code));
			String path = path(request);
			if (path != null) {
				writer.printf("PATH: %s%n", path);
			}
		}

		static void writeHtml(Writer writer, int code) throws IOException {
			String reason = reason(code);
			writer.write("<html>\n<head><title>Error " + code + " " + reason + "</title></head>\n");
			writer.write("<body><h2>HTTP ERROR " + code + " " + reason + "</h2></body>\n</html>\n");
		}

		static String reason(int code) {
			String reason = HttpStatus.getMessage(code);
			return reason != null ? reason : "Error";
		}

		// The path is client-controlled; sanitize it for markup contexts before quoting, the same
		// way Jetty's default writers do. A request rejected during parsing may have no usable URI.
		private static String path(Request request) {
			try {
				HttpURI uri = request != null ? request.getHttpURI() : null;
				String path = uri != null ? uri.getPath() : null;
				return path != null ? StringUtil.sanitizeXmlString(path) : null;
			} catch (RuntimeException e) {
				return null;
			}
		}

		private static String quote(String value) {
			return HttpField.NAME_VALUE_TOKENIZER.quote(value);
		}
	}
}
