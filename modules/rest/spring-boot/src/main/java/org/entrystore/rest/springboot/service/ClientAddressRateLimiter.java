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

package org.entrystore.rest.springboot.service;

import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.base.CharMatcher;
import com.google.common.net.InetAddresses;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rate limiter keyed by client address, {@code request.getRemoteAddr()}. Behind
 * {@code server.forward-headers-strategy: framework} that is the unvalidated {@code Forwarded: for=} or
 * leftmost {@code X-Forwarded-For} value. An IP address counts against the budget of its canonical text, so
 * every spelling of one address shares a budget, and a port sent with it is ignored. Any other value, such as
 * RFC 7239 {@code unknown}, an obfuscated identifier like {@code _hidden}, an IPv6 address with a zone or a
 * malformed header, counts against {@link #UNKNOWN_KEY}. A key is therefore an IP address or
 * {@link #UNKNOWN_KEY}, never the text a client sent.
 */
public abstract class ClientAddressRateLimiter extends FixedWindowRateLimiter {

	private static final int MAX_ADDRESS_LENGTH = "[0000:0000:0000:0000:0000:ffff:255.255.255.255]:65535".length();

	private static final CharMatcher ADDRESS_CHARS = CharMatcher.anyOf("0123456789abcdefABCDEF.:[]");

	/** {@code [v6]:port} (group 1), or {@code v4:port} in optional brackets (group 2). */
	private static final Pattern WITH_PORT = Pattern.compile("(\\[[0-9a-fA-F:.]+]):\\d{1,5}|\\[?([0-9.]+):\\d{1,5}]?");

	protected ClientAddressRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker) {
		super(max, window, rateLimitName, ticker);
	}

	protected ClientAddressRateLimiter(int max, Duration window, String rateLimitName, Ticker ticker,
			boolean warnWhenDisabled) {
		super(max, window, rateLimitName, ticker, warnWhenDisabled);
	}

	/** Spring's forwarded-header handling and Jetty both bracket IPv6, which {@code ofLiteral} accepts. */
	@Override
	protected final String permitKey(@Nullable String address) {
		// ofLiteral throws twice per rejected value and looks up a named IPv6 zone in the network interfaces
		if (address == null || address.length() > MAX_ADDRESS_LENGTH || !ADDRESS_CHARS.matchesAllOf(address)) {
			return UNKNOWN_KEY;
		}
		// Spring keeps a port sent in X-Forwarded-For (Azure Application Gateway) and brackets "v4:port"
		Matcher withPort = WITH_PORT.matcher(address);
		String literal = !withPort.matches() ? address
				: withPort.group(1) != null ? withPort.group(1) : withPort.group(2);
		try {
			return InetAddresses.toAddrString(InetAddress.ofLiteral(literal));
		} catch (IllegalArgumentException e) {
			return UNKNOWN_KEY;
		}
	}
}
