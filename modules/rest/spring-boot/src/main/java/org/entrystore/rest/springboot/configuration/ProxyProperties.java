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

import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.config.Settings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bindings for {@code entrystore.proxy.*}: the outbound-fetch limits, consumed by
 * {@code ProxyService} (response-size cap), {@code SsrfSafeHttpClient} (redirect cap) and
 * {@code SsrfValidator} (socket timeouts), and the proxy/SSRF whitelists.
 *
 * <p><b>Outbound-fetch limits.</b> All defaults are the constants these keys replaced, so existing
 * deployments see no change. The timeouts apply per hop on <b>both</b> outbound paths that go through
 * {@code SsrfValidator.openPinnedConnection}: {@code GET /proxy} (and its context-scoped form) and
 * {@code DELETE /{context-id}/resource/{entry-id}?proxy=true}. Establishing all hops costs at worst
 * roughly {@code (maxRedirects + 1) × connectTimeout}; {@code readTimeout} bounds each socket read
 * rather than the exchange, so total wall time is <b>not</b> bounded by it — a slow-drip upstream can
 * hold a request thread until {@code maxResponseSize} is reached. Plus DNS resolution, which no timeout
 * here covers. Lowering {@code maxResponseSize} bounds the read loop; lowering the timeouts alone does
 * not. {@code maxResponseSize} applies to {@code GET /proxy} only — the resource-DELETE path never reads
 * a response body.
 *
 * <p><b>Whitelists.</b> EntryStore expresses lists in the legacy indexed form
 * ({@code ...whitelist.local.1=host}, {@code ...whitelist.local.2=...}), which Spring's binder reads
 * into a {@link Map} keyed by the numeric suffix rather than into a {@code List}. Consumers use the
 * {@code *Whitelist()} accessors, which expose the map values; an absent setting binds to an empty map,
 * which on the two proxy-GET whitelists means nothing is whitelisted. It does not mean nothing is
 * trusted on the DELETE path: {@code SsrfValidator} additionally auto-trusts the origin parsed from
 * {@code entrystore.rowstore.url} there — see {@link #deleteWhitelist()}.
 *
 * <p>Only the indexed form binds, and several config shapes changed meaning relative to
 * {@code Config.getStringList} — here that can whitelist a host the legacy reader ignored, and a
 * whitelisted host suppresses the SSRF hostname blacklist <em>and</em> the resolved-address
 * loopback/site-local/link-local checks. {@link IndexedListConfigValidator} documents the shapes and
 * reports them at startup.
 */
@Slf4j
@ConfigurationProperties(prefix = "entrystore.proxy")
public record ProxyProperties(
		@DefaultValue("10MB") DataSize maxResponseSize,
		@DefaultValue("15") int maxRedirects,
		@DurationUnit(ChronoUnit.SECONDS) @DefaultValue("30s") Duration connectTimeout,
		@DurationUnit(ChronoUnit.SECONDS) @DefaultValue("60s") Duration readTimeout,
		Whitelist whitelist,
		RemoteResource remoteResource
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
		// The binder leaves an absent nested record null rather than instantiating it.
		whitelist = (whitelist == null) ? new Whitelist(null, null) : whitelist;
		remoteResource = (remoteResource == null) ? new RemoteResource(null) : remoteResource;
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

	public record Whitelist(Map<String, String> local, Map<String, String> anonymous) {

		public Whitelist {
			// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
			local = (local == null) ? Map.of() : Map.copyOf(local);
			anonymous = (anonymous == null) ? Map.of() : Map.copyOf(anonymous);
		}
	}

	public record RemoteResource(Delete delete) {

		public RemoteResource {
			delete = (delete == null) ? new Delete(null) : delete;
		}

		public record Delete(Map<String, String> whitelist) {

			public Delete {
				whitelist = (whitelist == null) ? Map.of() : Map.copyOf(whitelist);
			}
		}
	}

	/**
	 * Hosts exempt from the SSRF blacklist on the proxy GET path
	 * ({@code entrystore.proxy.whitelist.local.*}), lower-cased with blank entries skipped.
	 */
	public Set<String> localWhitelist() {
		return toHostSet(whitelist.local(), Settings.PROXY_WHITELIST_LOCAL);
	}

	/**
	 * Hosts guest users may proxy to ({@code entrystore.proxy.whitelist.anonymous.*}),
	 * lower-cased with blank entries skipped.
	 */
	public Set<String> anonymousWhitelist() {
		return toHostSet(whitelist.anonymous(), Settings.PROXY_WHITELIST_ANONYMOUS);
	}

	/**
	 * Origins trusted on the remote-resource DELETE path
	 * ({@code entrystore.proxy.remote-resource.delete.whitelist.*}), as raw origin URLs —
	 * {@code SsrfValidator} parses and canonicalizes them, then tests membership. Iteration order is
	 * unspecified: {@code Map.copyOf} randomises it per JVM.
	 */
	public Collection<String> deleteWhitelist() {
		return remoteResource.delete().whitelist().values();
	}

	private static Set<String> toHostSet(Map<String, String> entries, String settingKeyForLog) {
		Set<String> result = new HashSet<>();
		for (String entry : entries.values()) {
			if (entry.isBlank()) {
				log.warn("Skipping blank entry in {}", settingKeyForLog);
				continue;
			}
			result.add(entry.toLowerCase(Locale.ROOT));
		}
		return Set.copyOf(result);
	}
}
