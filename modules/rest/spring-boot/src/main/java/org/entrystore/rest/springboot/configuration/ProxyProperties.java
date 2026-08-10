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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Bindings for the outbound-fetch limits under {@code entrystore.proxy.*}, consumed by
 * {@code ProxyService} (response-size cap), {@code SsrfSafeHttpClient} (redirect cap) and
 * {@code SsrfValidator} (socket timeouts).
 *
 * <p>All defaults are the constants these keys replaced, so existing deployments see no change.
 *
 * <p>The timeouts apply per hop on <b>both</b> outbound paths that go through
 * {@code SsrfValidator.openPinnedConnection}: {@code GET /proxy} (and its context-scoped form) and
 * {@code DELETE /{context-id}/resource/{entry-id}?proxy=true}. Establishing all hops costs at worst
 * roughly {@code (maxRedirects + 1) × connectTimeout}; {@code readTimeout} bounds each socket read
 * rather than the exchange, so total wall time is <b>not</b> bounded by it — a slow-drip upstream can
 * hold a request thread until {@code maxResponseSize} is reached. Plus DNS resolution, which no timeout
 * here covers. Lowering {@code maxResponseSize} bounds the read loop; lowering the timeouts alone does
 * not. {@code maxResponseSize} applies to {@code GET /proxy} only — the resource-DELETE path never reads
 * a response body.
 *
 * <p>This prefix is shared with the SSRF whitelists ({@code entrystore.proxy.whitelist.anonymous},
 * {@code .whitelist.local}, {@code entrystore.proxy.remote-resource.delete.whitelist.N}), which are
 * still read through the legacy {@code Config} wrapper and are simply not bound here.
 */
@ConfigurationProperties(prefix = "entrystore.proxy")
public record ProxyProperties(
		@DefaultValue("10MB") DataSize maxResponseSize,
		@DefaultValue("15") int maxRedirects,
		@DurationUnit(ChronoUnit.SECONDS) @DefaultValue("30s") Duration connectTimeout,
		@DurationUnit(ChronoUnit.SECONDS) @DefaultValue("60s") Duration readTimeout
) {

	/** Guards against an operator turning one request into an unbounded chain of outbound connections. */
	private static final int MAX_REDIRECTS_CEILING = 50;

	/**
	 * Bounds per-request heap, and is also what keeps the cap enforceable at all: the body is
	 * accumulated in a {@code ByteArrayOutputStream}, whose backing array is int-indexed, so without a
	 * ceiling a large enough cap would throw {@code OutOfMemoryError} out of {@code out.write} before
	 * the size check could ever trip — an unmapped 500 instead of the 502 the cap exists to produce.
	 */
	private static final DataSize MAX_RESPONSE_SIZE_CEILING = DataSize.ofMegabytes(512);

	/**
	 * Upper bound on the timeouts. Also what makes {@link #connectTimeoutMillis()} safe: those return
	 * {@code int} because that is what {@code URLConnection} takes, and an hour is far short of the
	 * ~24.8 days at which a millisecond count would overflow.
	 */
	private static final Duration TIMEOUT_CEILING = Duration.ofHours(1);

	public ProxyProperties {
		if (maxResponseSize == null || maxResponseSize.toBytes() < 1) {
			throw new IllegalArgumentException(
					"entrystore.proxy.max-response-size must be positive, got " + maxResponseSize);
		}
		if (maxResponseSize.compareTo(MAX_RESPONSE_SIZE_CEILING) > 0) {
			// Ceiling spelled in MB rather than via DataSize.toString(), which renders raw bytes: the
			// operator writes this key as "512MB", so that is what the remedy should read as.
			throw new IllegalArgumentException("entrystore.proxy.max-response-size must not exceed "
					+ MAX_RESPONSE_SIZE_CEILING.toMegabytes() + "MB — the response is buffered in memory "
					+ "per request, got " + maxResponseSize);
		}
		if (maxRedirects < 0 || maxRedirects > MAX_REDIRECTS_CEILING) {
			throw new IllegalArgumentException("entrystore.proxy.max-redirects must be between 0 and "
					+ MAX_REDIRECTS_CEILING + ", got " + maxRedirects);
		}
		requireBoundedAndPositive(connectTimeout, "entrystore.proxy.connect-timeout");
		requireBoundedAndPositive(readTimeout, "entrystore.proxy.read-timeout");
	}

	private static void requireBoundedAndPositive(Duration value, String key) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(key + " must be positive, got " + value);
		}
		if (value.compareTo(TIMEOUT_CEILING) > 0) {
			throw new IllegalArgumentException(key + " must not exceed " + TIMEOUT_CEILING + ", got " + value);
		}
	}

	/**
	 * The connect timeout as the {@code int} milliseconds {@code URLConnection} takes. Bounded by the
	 * validation above well short of overflow.
	 */
	public int connectTimeoutMillis() {
		return (int) connectTimeout.toMillis();
	}

	/** The read timeout as the {@code int} milliseconds {@code URLConnection} takes. */
	public int readTimeoutMillis() {
		return (int) readTimeout.toMillis();
	}
}
