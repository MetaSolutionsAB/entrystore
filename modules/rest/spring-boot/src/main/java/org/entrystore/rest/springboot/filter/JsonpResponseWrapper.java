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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.util.HttpUtil;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Wraps a JSON response body in a JSONP callback (<code>callback(&lt;json&gt;)</code>) while the body is written
 * through to the response, so that no body is held in memory. Whether to wrap is decided at the first write or flush
 * of the body, or in {@link #finish()} for a response the handler wrote no body to: a response is wrapped when its
 * status carries a whole body, it has no {@code Content-Encoding} and its media type is one of
 * {@link #JSON_MEDIA_TYPES}; any other passes through as the handler wrote it. Every response decided before it is
 * committed gets the {@code Cache-Control} of {@link #preventStorage}.
 * <p>
 * A wrapped response carries the {@code Content-Length} the handler declared plus the length of the callback, or none
 * if the handler declared none and wrote a body; an empty body completed in {@link #finish()} carries the length of
 * <code>callback()</code>. It carries no {@code Digest}, which is that of the unwrapped body. A reset of the buffer
 * before the response is committed reopens the decision, so the error body that Spring MVC writes after a reset is
 * decided on its own.
 * <p>
 * The caller calls {@link #finish()} once the handler has returned normally, or {@link #passThrough()} when the
 * request went async.
 */
final class JsonpResponseWrapper extends HttpServletResponseWrapper {

	private static final String JSONP_CONTENT_TYPE = "application/javascript";

	// JSON media types the original Restlet JSCallbackFilter wrapped. SPARQL results
	// (application/sparql-results+json) were never in this set.
	private static final Set<String> JSON_MEDIA_TYPES = Set.of(
			MediaType.APPLICATION_JSON_VALUE,
			"application/ld+json",
			"application/rdf+json");

	// No body, or a byte range of one, which the callback would no longer match the Content-Range of.
	private static final Set<Integer> UNWRAPPED_STATUSES = Set.of(
			HttpStatus.NO_CONTENT.value(),
			HttpStatus.PARTIAL_CONTENT.value(),
			HttpStatus.NOT_MODIFIED.value());

	private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

	private enum Mode {UNDECIDED, PASS_THROUGH, WRAP}

	private final HttpServletResponse response;
	private final String prefix;

	private Mode mode = Mode.UNDECIDED;
	// The type the handler sets while the body is wrapped, which the response gets back if the body is discarded.
	private @Nullable String handlerContentType;
	private Charset charset = StandardCharsets.UTF_8;
	private boolean prefixWritten;
	private boolean suffixWritten;
	private @Nullable ServletOutputStream body;
	private @Nullable PrintWriter bodyWriter;
	private @Nullable ServletOutputStream outputStream;
	private @Nullable PrintWriter writer;

	JsonpResponseWrapper(HttpServletResponse response, String callback) {
		super(response);
		this.response = response;
		this.prefix = callback + "(";
	}

	/**
	 * Completes the response once the handler has returned normally: writes the closing parenthesis of a wrapped body,
	 * and decides a response the handler wrote no body to, so an empty JSON body becomes <code>callback()</code>. A
	 * response committed around this wrapper, as by {@code sendError}, is left as it is.
	 */
	void finish() throws IOException {
		decide(true);
		if (mode != Mode.WRAP || suffixWritten) {
			return;
		}
		if (body == null && bodyWriter == null) {
			body = response.getOutputStream();
		}
		writePrefixIfDue();
		suffixWritten = true;
		writeAffix(")");
	}

	/**
	 * Leaves a response that is not decided yet unwrapped, for a handler that goes on writing it after the filter
	 * returned, and so could not be given a closing parenthesis.
	 */
	void passThrough() {
		if (mode == Mode.UNDECIDED) {
			mode = Mode.PASS_THROUGH;
		}
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {
		if (outputStream == null) {
			body = response.getOutputStream();
			outputStream = new JsonpOutputStream(body);
		}
		return outputStream;
	}

	@Override
	public PrintWriter getWriter() throws IOException {
		if (writer == null) {
			bodyWriter = response.getWriter();
			writer = new PrintWriter(new JsonpWriter(bodyWriter));
		}
		return writer;
	}

	@Override
	public void flushBuffer() throws IOException {
		beforeBody();
		super.flushBuffer();
	}

	/**
	 * Discards the body and reopens the decision. The response loses the length of the discarded body, and a wrapped
	 * one gets back the type the handler set. The {@code Digest} that wrapping removed and the {@code Cache-Control}
	 * of {@link #preventStorage} are not restored.
	 */
	@Override
	public void resetBuffer() {
		super.resetBuffer();
		if (mode == Mode.WRAP) {
			response.setContentType(handlerContentType);
			response.setHeader(CONTENT_TYPE_OPTIONS, null);
		}
		response.setContentLengthLong(-1);
		undecide();
	}

	@Override
	public void reset() {
		super.reset();
		undecide();
	}

	@Override
	public void setContentType(@Nullable String type) {
		if (mode == Mode.WRAP) {
			handlerContentType = type;
		} else {
			super.setContentType(type);
		}
	}

	@Override
	public void setHeader(String name, @Nullable String value) {
		if (isWrappedContentType(name)) {
			handlerContentType = value;
		} else {
			super.setHeader(name, value);
		}
	}

	@Override
	public void addHeader(String name, @Nullable String value) {
		if (isWrappedContentType(name)) {
			handlerContentType = value;
		} else {
			super.addHeader(name, value);
		}
	}

	/**
	 * Applied to each response decided before it is committed, wrapped or not: a wrapped body no longer matches the
	 * controller's strong ETag / Last-Modified, so caches and clients must not revalidate against a stale validator.
	 * Keeps the {@code private} that {@link CacheControlFilter} stamps on authenticated responses, and any value a
	 * controller set other than the revalidation it asks of anonymous clients.
	 */
	private static void preventStorage(HttpServletResponse response) {
		String cacheControl = response.getHeader(HttpHeaders.CACHE_CONTROL);
		if (cacheControl == null || CacheControlFilter.CACHE_CONTROL_ANONYMOUS.equals(cacheControl)) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		} else if (CacheControlFilter.CACHE_CONTROL_AUTHENTICATED.equals(cacheControl)) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
		}
	}

	private void beforeBody() throws IOException {
		decide(false);
		writePrefixIfDue();
	}

	/**
	 * @param emptyBody whether the handler is known to have written no body, so a wrapped length is known too
	 */
	private void decide(boolean emptyBody) {
		if (mode != Mode.UNDECIDED) {
			return;
		}
		mode = Mode.PASS_THROUGH;
		if (response.isCommitted()) {
			return;
		}
		preventStorage(response);
		// Null for a missing or unparseable type; Set.of(...).contains(null) throws.
		String mediaType = HttpUtil.normalizeMediaType(response.getContentType());
		// The parentheses would be plain text around encoded bytes.
		String encoding = response.getHeader(HttpHeaders.CONTENT_ENCODING);
		if (!UNWRAPPED_STATUSES.contains(response.getStatus())
				&& (encoding == null || "identity".equalsIgnoreCase(encoding))
				&& mediaType != null && JSON_MEDIA_TYPES.contains(mediaType)) {
			wrap(emptyBody);
		}
	}

	private void wrap(boolean emptyBody) {
		mode = Mode.WRAP;
		handlerContentType = response.getContentType();
		long bodyLength = emptyBody ? 0 : declaredContentLength();
		// A writer keeps the charset it was opened with.
		if (bodyWriter != null) {
			charset = Charset.forName(response.getCharacterEncoding());
		} else {
			charset = Objects.requireNonNullElse(MediaType.parseMediaType(handlerContentType).getCharset(),
					StandardCharsets.UTF_8);
		}
		response.setContentType(JSONP_CONTENT_TYPE);
		response.setCharacterEncoding(charset.name());
		response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
		response.setHeader("Digest", null);
		if (bodyLength >= 0) {
			response.setContentLengthLong(bodyLength + prefix.getBytes(charset).length + ")".getBytes(charset).length);
		}
	}

	private long declaredContentLength() {
		String length = response.getHeader(HttpHeaders.CONTENT_LENGTH);
		return length != null ? Long.parseLong(length) : -1;
	}

	private void undecide() {
		mode = Mode.UNDECIDED;
		prefixWritten = false;
		suffixWritten = false;
	}

	private boolean isWrappedContentType(String name) {
		return mode == Mode.WRAP && HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name);
	}

	// Without a channel yet, as after a flushBuffer() before any write, finish() writes the prefix.
	private void writePrefixIfDue() throws IOException {
		if (mode == Mode.WRAP && !prefixWritten && (body != null || bodyWriter != null)) {
			prefixWritten = true;
			writeAffix(prefix);
		}
	}

	private void writeAffix(String affix) throws IOException {
		if (bodyWriter != null) {
			bodyWriter.write(affix);
		} else if (body != null) {
			body.write(affix.getBytes(charset));
		}
	}

	@RequiredArgsConstructor
	private final class JsonpOutputStream extends ServletOutputStream {

		private final ServletOutputStream delegate;

		@Override
		public void write(int b) throws IOException {
			beforeBody();
			delegate.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			beforeBody();
			delegate.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			beforeBody();
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			finish();
			delegate.close();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
			delegate.setWriteListener(writeListener);
		}
	}

	@RequiredArgsConstructor
	private final class JsonpWriter extends Writer {

		private final PrintWriter delegate;

		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			beforeBody();
			delegate.write(cbuf, off, len);
		}

		@Override
		public void write(String str, int off, int len) throws IOException {
			beforeBody();
			delegate.write(str, off, len);
		}

		@Override
		public void flush() throws IOException {
			beforeBody();
			delegate.flush();
		}

		@Override
		public void close() throws IOException {
			finish();
			delegate.close();
		}
	}
}
