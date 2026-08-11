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

package org.entrystore.rest.springboot.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SsrfValidator {

	// Provides the whitelists and the timeouts applied per hop on both outbound paths that use
	// openPinnedConnection: GET /proxy and DELETE /{context-id}/resource/{entry-id}?proxy=true.
	private final ProxyProperties proxyProperties;
	private final String rowstoreUrl;

	private Set<String> proxyHostWhitelist;
	private Set<Origin> deleteOriginWhitelist;
	private Origin rowstoreOrigin;

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private static final List<Pattern> BLACKLIST_REGEX = List.of(
			Pattern.compile("^localhost$"),                                   // localhost
			Pattern.compile("(.+)\\.local$"),                                // any local domains
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"), // IPv4
			Pattern.compile("^\\d+$"),                                       // numeric IPv4 representation (e.g. 2130706433)
			Pattern.compile(":")                                             // IPv6
	);

	/**
	 * Canonical origin record used for DELETE-path trust decisions. The compact constructor
	 * enforces non-null scheme/host and port {@code > 0}; producers ({@link #parseOrigin} and
	 * {@link #toOrigin}) lowercase scheme/host and substitute the scheme's default port so
	 * {@code http://x} and {@code http://x:80} compare equal.
	 */
	record Origin(String scheme, String host, int port) {
		Origin {
			Objects.requireNonNull(scheme, "scheme");
			Objects.requireNonNull(host, "host");
			if (port <= 0) {
				throw new IllegalArgumentException("port must be > 0, got " + port);
			}
		}
	}

	/**
	 * Validated proxy/DELETE target: the parsed URI, the lowercased host (consistent with
	 * {@code uri.getHost().toLowerCase(Locale.ROOT)}), and the DNS-resolved address that
	 * callers MUST pin connections to. All fields are non-null.
	 */
	public record ValidatedTarget(URI uri, String host, InetAddress resolved) {
		public ValidatedTarget {
			Objects.requireNonNull(uri, "uri");
			Objects.requireNonNull(host, "host");
			Objects.requireNonNull(resolved, "resolved");
		}
	}

	public SsrfValidator(ProxyProperties proxyProperties,
			@Value("${entrystore.rowstore.url:#{null}}") String rowstoreUrl) {
		this.proxyProperties = proxyProperties;
		this.rowstoreUrl = rowstoreUrl;
	}

	@PostConstruct
	void init() {
		proxyHostWhitelist = proxyProperties.localWhitelist();
		if (!proxyHostWhitelist.isEmpty()) {
			log.info("Proxy GET local whitelist (host-only) initialized with: {}", String.join(", ", proxyHostWhitelist));
		}

		deleteOriginWhitelist = toOriginSet(proxyProperties.deleteWhitelist(), Settings.PROXY_REMOTE_RESOURCE_DELETE_WHITELIST);
		if (!deleteOriginWhitelist.isEmpty()) {
			log.info("Resource DELETE origin whitelist initialized with: {}",
					deleteOriginWhitelist.stream().map(Origin::toString).toList());
		}

		rowstoreOrigin = parseOrigin(rowstoreUrl);
		if (rowstoreOrigin != null) {
			log.info("Resource DELETE auto-trusts RowStore origin: {}", rowstoreOrigin);
		} else if (rowstoreUrl != null && !rowstoreUrl.isBlank()) {
			log.warn("entrystore.rowstore.url is set but did not parse as an http(s) origin; "
					+ "DELETE auto-trust is disabled. Value: {}", rowstoreUrl);
		}

		log.info("SSRF blacklist consists of following regular expressions: {}", BLACKLIST_REGEX);
	}

	private static Set<Origin> toOriginSet(Collection<String> values, String settingKeyForLog) {
		Set<Origin> result = new LinkedHashSet<>();
		for (String entry : values) {
			Origin origin = parseOrigin(entry);
			if (origin != null) {
				result.add(origin);
			} else if (!entry.isBlank()) {
				log.warn("Skipping malformed origin in {}: {}", settingKeyForLog, entry);
			}
		}
		return result;
	}

	/**
	 * Canonicalizes a URL into an {@link Origin} (scheme/host lowercased with Locale.ROOT;
	 * default port substituted). Returns {@code null} for null/blank input, malformed input,
	 * missing scheme/host, or any scheme outside {@link #ALLOWED_SCHEMES}, so callers can
	 * log-and-skip.
	 */
	static Origin parseOrigin(String urlString) {
		if (urlString == null || urlString.isBlank()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(urlString.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		if (uri.getScheme() == null || uri.getHost() == null) {
			return null;
		}
		return toOrigin(uri);
	}

	/**
	 * Canonicalizes an already-parsed URI into an {@link Origin}. Returns {@code null} if the
	 * URI's scheme is not in {@link #ALLOWED_SCHEMES}. Caller must ensure {@code uri.getHost()}
	 * is non-null (the producer of the URI typically already enforces this).
	 */
	private static Origin toOrigin(URI uri) {
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!ALLOWED_SCHEMES.contains(scheme)) {
			return null;
		}
		int port = uri.getPort();
		if (port == -1) {
			port = defaultPort(scheme);
		}
		return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
	}

	private static int defaultPort(String scheme) {
		return switch (scheme) {
			case "http" -> 80;
			case "https" -> 443;
			default -> -1;
		};
	}

	/**
	 * Cheap pre-auth validation: parses the URL and rejects malformed input, disallowed
	 * schemes, embedded credentials, and missing host. Does NOT perform DNS resolution or
	 * blacklist checks — callers may run this before authorization, then call
	 * {@link #resolveForProxy(URI)} or {@link #resolveForDelete(URI)} post-auth.
	 */
	public URI parseAndValidateUrl(String url) {
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new BadRequestException("Malformed URL: " + url);
		}
		String scheme = uri.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
			throw new BadRequestException("Only http and https URLs are supported");
		}
		if (uri.getUserInfo() != null) {
			throw new BadRequestException("URLs with embedded credentials are not allowed");
		}
		if (uri.getHost() == null) {
			throw new BadRequestException("URL has no host: " + url);
		}
		return uri;
	}

	/**
	 * Proxy GET path resolve step. Runs DNS resolution and applies the host blacklist; a
	 * whitelisted host (per {@link Settings#PROXY_WHITELIST_LOCAL}) suppresses BOTH the
	 * hostname regex blacklist AND the resolved-address loopback/site-local/link-local checks.
	 * Heavyweight — call after authorization.
	 */
	public ValidatedTarget resolveForProxy(URI uri) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return validate(uri, host, proxyHostWhitelist.contains(host));
	}

	/**
	 * Resource DELETE path resolve step. Runs DNS resolution and applies the host blacklist;
	 * a request whose origin matches {@link Settings#PROXY_REMOTE_RESOURCE_DELETE_WHITELIST}
	 * or the implicit {@link Settings#ROWSTORE_URL} origin suppresses BOTH the hostname
	 * blacklist AND the resolved-address loopback/site-local/link-local checks. Heavyweight —
	 * call after authorization.
	 */
	public ValidatedTarget resolveForDelete(URI uri) {
		Origin origin = toOrigin(uri);
		if (origin == null) {
			// uri.getScheme() failed ALLOWED_SCHEMES — shouldn't happen if parseAndValidateUrl was called first
			throw new BadRequestException("Only http and https URLs are supported");
		}
		boolean isTrustedOrigin = deleteOriginWhitelist.contains(origin)
				|| origin.equals(rowstoreOrigin);
		return validate(uri, origin.host(), isTrustedOrigin);
	}

	/**
	 * Combined parse + resolve for the proxy GET path. Equivalent to
	 * {@code resolveForProxy(parseAndValidateUrl(url))}. Used by {@code ProxyService.fetchUrl}
	 * on each redirect hop where parse and resolve always happen together.
	 */
	public ValidatedTarget validateForProxy(String url) {
		return resolveForProxy(parseAndValidateUrl(url));
	}

	/**
	 * Combined parse + resolve for the resource DELETE path. Equivalent to
	 * {@code resolveForDelete(parseAndValidateUrl(url))}.
	 */
	public ValidatedTarget validateForDelete(String url) {
		return resolveForDelete(parseAndValidateUrl(url));
	}

	private ValidatedTarget validate(URI uri, String host, boolean trusted) {
		return new ValidatedTarget(uri, host, doValidateHost(host, trusted));
	}

	private InetAddress doValidateHost(String host, boolean isWhitelistedLocal) {
		if (!isWhitelistedLocal) {
			for (Pattern p : BLACKLIST_REGEX) {
				if (p.matcher(host).find()) {
					throw new ForbiddenException("Access denied: host is blacklisted");
				}
			}
		}

		InetAddress[] allAddresses;
		try {
			allAddresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			log.warn("Failed to resolve host: {}", host);
			throw new ForbiddenException("Access denied: host cannot be resolved");
		}

		for (InetAddress addr : allAddresses) {
			if (isDisallowedAddress(addr, isWhitelistedLocal)) {
				throw new ForbiddenException("Access denied: host resolves to a disallowed address");
			}
		}

		return allAddresses[0];
	}

	private boolean isDisallowedAddress(InetAddress address, boolean whitelistedLocal) {
		if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		if (!whitelistedLocal) {
			return address.isSiteLocalAddress() ||
					address.isLoopbackAddress() ||
					address.isLinkLocalAddress();
		}
		return false;
	}

	/**
	 * Opens an {@link HttpURLConnection} pinned to the resolved IP address with standard
	 * SSRF-safe defaults: connect/read timeouts applied, automatic redirects disabled, original
	 * hostname preserved in the {@code Host} header for virtual hosting, and (for HTTPS) SNI +
	 * cert hostname verification against the original hostname. Callers MUST re-run
	 * {@link #validateForProxy(String)} / {@link #validateForDelete(String)} on every redirect
	 * hop before reusing this method; the SSRF guarantee depends on it.
	 */
	public HttpURLConnection openPinnedConnection(URI originalUri, InetAddress resolved)
			throws IOException, URISyntaxException {
		URI pinnedUri = buildPinnedUri(originalUri, resolved);
		HttpURLConnection conn = (HttpURLConnection) pinnedUri.toURL().openConnection();
		conn.setInstanceFollowRedirects(false);
		conn.setConnectTimeout(proxyProperties.connectTimeoutMillis());
		conn.setReadTimeout(proxyProperties.readTimeoutMillis());
		conn.setRequestProperty("Host", buildHostHeader(originalUri));
		if (conn instanceof HttpsURLConnection httpsConn) {
			configureSsl(httpsConn, originalUri.getHost());
		}
		return conn;
	}

	private URI buildPinnedUri(URI originalUri, InetAddress resolved) throws URISyntaxException {
		String ipHost = (resolved instanceof Inet6Address)
				? "[" + resolved.getHostAddress() + "]"
				: resolved.getHostAddress();
		return new URI(
				originalUri.getScheme(),
				null,
				ipHost,
				originalUri.getPort(),
				originalUri.getRawPath(),
				originalUri.getRawQuery(),
				originalUri.getRawFragment()
		);
	}

	private String buildHostHeader(URI uri) {
		int port = uri.getPort();
		String scheme = uri.getScheme();
		boolean isDefaultPort = port == -1
				|| (scheme != null && port == defaultPort(scheme.toLowerCase(Locale.ROOT)));
		return isDefaultPort ? uri.getHost() : uri.getHost() + ":" + port;
	}

	private void configureSsl(HttpsURLConnection httpsConn, String originalHost) {
		SSLSocketFactory defaultFactory = httpsConn.getSSLSocketFactory();
		httpsConn.setSSLSocketFactory(new SniSSLSocketFactory(defaultFactory, originalHost));

		HostnameVerifier defaultVerifier = httpsConn.getHostnameVerifier();
		httpsConn.setHostnameVerifier((hostname, session) ->
				defaultVerifier.verify(originalHost, session));
	}

	void setProxyHostWhitelist(Set<String> proxyHostWhitelist) {
		this.proxyHostWhitelist = proxyHostWhitelist;
	}

	void setDeleteOriginWhitelist(Set<Origin> deleteOriginWhitelist) {
		this.deleteOriginWhitelist = deleteOriginWhitelist;
	}

	void setRowstoreOrigin(Origin rowstoreOrigin) {
		this.rowstoreOrigin = rowstoreOrigin;
	}

	private static void setSniHostname(Socket socket, String hostname) {
		if (socket instanceof SSLSocket sslSocket) {
			SSLParameters params = sslSocket.getSSLParameters();
			params.setServerNames(List.of(new SNIHostName(hostname)));
			sslSocket.setSSLParameters(params);
		}
	}

	private static class SniSSLSocketFactory extends SSLSocketFactory {

		private final SSLSocketFactory delegate;
		private final String hostname;

		SniSSLSocketFactory(SSLSocketFactory delegate, String hostname) {
			this.delegate = delegate;
			this.hostname = hostname;
		}

		@Override
		public String[] getDefaultCipherSuites() {
			return delegate.getDefaultCipherSuites();
		}

		@Override
		public String[] getSupportedCipherSuites() {
			return delegate.getSupportedCipherSuites();
		}

		@Override
		public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
			// Pass original hostname (not the pinned IP in `host`) so the JDK sets
			// SNI and uses the correct hostname for TLS session caching/verification.
			return delegate.createSocket(s, hostname, port, autoClose);
		}

		@Override
		public Socket createSocket(String host, int port) throws IOException {
			Socket socket = delegate.createSocket(host, port);
			setSniHostname(socket, hostname);
			return socket;
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
			Socket socket = delegate.createSocket(host, port, localHost, localPort);
			setSniHostname(socket, hostname);
			return socket;
		}

		@Override
		public Socket createSocket(InetAddress host, int port) throws IOException {
			Socket socket = delegate.createSocket(host, port);
			setSniHostname(socket, hostname);
			return socket;
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
			Socket socket = delegate.createSocket(address, port, localAddress, localPort);
			setSniHostname(socket, hostname);
			return socket;
		}

		@Override
		public Socket createSocket() throws IOException {
			Socket socket = delegate.createSocket();
			setSniHostname(socket, hostname);
			return socket;
		}
	}
}
