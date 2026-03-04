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

package org.entrystore.rest.standalone.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.standalone.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;
	private final ContextService contextService;

	private Set<String> whitelistAnon;
	private Set<String> whitelistLocal;

	private static final int MAX_REDIRECTS = 15;
	private static final int CONNECT_TIMEOUT_MS = 30_000;
	private static final int READ_TIMEOUT_MS = 60_000;
	private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024; // 10 MB

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	private static final List<Pattern> BLACKLIST_REGEX = List.of(
			Pattern.compile("^localhost$"),                                   // localhost
			Pattern.compile("(.+)\\.local$"),                                // any local domains
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"), // IPv4
			Pattern.compile("^\\d+$"),                                       // numeric IPv4 representation (e.g. 2130706433)
			Pattern.compile(":")                                             // IPv6
	);

	@PostConstruct
	void init() {
		whitelistAnon = loadWhitelist(Settings.PROXY_WHITELIST_ANONYMOUS);
		if (!whitelistAnon.isEmpty()) {
			log.info("Proxy whitelist for guest users initialized with following domains: {}; Requests to other domains require authentication",
					String.join(", ", whitelistAnon));
		} else {
			log.info("No domains provided for proxy whitelist; only authenticated users are allowed to perform proxy requests");
		}

		whitelistLocal = loadWhitelist(Settings.PROXY_WHITELIST_LOCAL);
		if (!whitelistLocal.isEmpty()) {
			log.info("Proxy local whitelist initialized with following domains: {}", String.join(", ", whitelistLocal));
		}

		log.info("Proxy blacklist consists of following regular expressions: {}", BLACKLIST_REGEX);
	}

	private Set<String> loadWhitelist(String settingsKey) {
		Set<String> result = new HashSet<>();
		for (String domain : repositoryManager.getConfiguration().getStringList(settingsKey)) {
			if (domain != null) {
				result.add(domain.toLowerCase());
			}
		}
		return result;
	}

	public void validateUrl(String url) {
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			throw new BadRequestException("Malformed URL: " + url);
		}
		String scheme = uri.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
			throw new BadRequestException("Only http and https URLs are supported");
		}
		if (uri.getUserInfo() != null) {
			throw new BadRequestException("URLs with embedded credentials are not allowed");
		}
	}

	public String extractHost(String url) {
		try {
			String host = new URI(url).getHost();
			if (host == null) {
				throw new BadRequestException("URL has no host: " + url);
			}
			return host.toLowerCase();
		} catch (URISyntaxException e) {
			throw new BadRequestException("Malformed URL: " + url);
		}
	}

	public void validateGlobalAccess(String host) {
		if (principalManager.getGuestUser().getURI().equals(principalManager.getAuthenticatedUserURI())) {
			if (!whitelistAnon.contains(host)) {
				throw new ForbiddenException("Guest user is not allowed to proxy requests to host: " + host);
			}
		}
	}

	public void validateContextAccess(String contextId) {
		Context context = contextService.getContextOrThrow(contextId);
		principalManager.checkAuthenticatedUserAuthorized(context.getEntry(),
				PrincipalManager.AccessProperty.ReadResource);
	}

	void setWhitelistLocal(Set<String> whitelistLocal) {
		this.whitelistLocal = whitelistLocal;
	}

	void setWhitelistAnon(Set<String> whitelistAnon) {
		this.whitelistAnon = whitelistAnon;
	}

	InetAddress resolveAndValidate(String host) {
		host = host.toLowerCase();
		boolean isWhitelistedLocal = whitelistLocal.contains(host);

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

	public ProxyResponse fetchUrl(String url, String acceptHeader) {
		return fetchUrl(url, acceptHeader, 0);
	}

	private ProxyResponse fetchUrl(String url, String acceptHeader, int redirectCount) {
		// Re-validate: on redirects the URL may differ from the original request
		validateUrl(url);

		String host = extractHost(url);
		InetAddress resolved = resolveAndValidate(host);

		if (redirectCount > MAX_REDIRECTS) {
			log.warn("More than {} redirect loops detected, aborting", MAX_REDIRECTS);
			throw new CustomResponseException("Too many redirects", HttpStatus.BAD_GATEWAY);
		}

		HttpURLConnection conn = null;
		try {
			URI originalUri = new URI(url);
			URI pinnedUri = buildPinnedUri(originalUri, resolved);

			conn = (HttpURLConnection) pinnedUri.toURL().openConnection();
			conn.setRequestMethod("GET");
			conn.setInstanceFollowRedirects(false);
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);

			// Set Host header to original hostname for virtual hosting
			conn.setRequestProperty("Host", buildHostHeader(originalUri));

			if (conn instanceof HttpsURLConnection httpsConn) {
				configureSsl(httpsConn, host);
			}

			if (acceptHeader != null) {
				conn.setRequestProperty("Accept", acceptHeader);
			}

			int status = conn.getResponseCode();

			if (status >= 300 && status < 400) {
				String location = conn.getHeaderField("Location");
				if (location != null) {
					// Resolve relative redirects against the original URL
					URI resolvedLocation = originalUri.resolve(location);
					log.debug("Request redirected to {}", resolvedLocation);
					return fetchUrl(resolvedLocation.toString(), acceptHeader, redirectCount + 1);
				}
				log.warn("Upstream returned {} redirect without Location header for URL: {}", status, url);
				throw new CustomResponseException("Upstream returned redirect without Location header", HttpStatus.BAD_GATEWAY);
			}

			String contentType = conn.getContentType();
			byte[] body;
			try (InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
				body = (is != null) ? readWithLimit(is) : new byte[0];
			}

			return new ProxyResponse(status, contentType, body);

		} catch (SocketTimeoutException | ConnectException e) {
			log.debug("Proxy request to {} timed out", url);
			throw new CustomResponseException("Gateway timeout", HttpStatus.GATEWAY_TIMEOUT);
		} catch (IOException | URISyntaxException e) {
			log.debug("Proxy request to {} failed: {}", url, e.getMessage());
			throw new CustomResponseException("Proxy request failed", HttpStatus.BAD_GATEWAY);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private URI buildPinnedUri(URI originalUri, InetAddress resolved) throws URISyntaxException {
		String ipHost = (resolved instanceof Inet6Address)
				? "[" + resolved.getHostAddress() + "]"
				: resolved.getHostAddress();
		return new URI(
				originalUri.getScheme(),
				null, // no userinfo
				ipHost,
				originalUri.getPort(),
				originalUri.getRawPath(),
				originalUri.getRawQuery(),
				originalUri.getRawFragment()
		);
	}

	private String buildHostHeader(URI uri) {
		String host = uri.getHost();
		int port = uri.getPort();
		boolean isDefaultPort = port == -1
				|| ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
				|| ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
		return isDefaultPort ? host : host + ":" + port;
	}

	private void configureSsl(HttpsURLConnection httpsConn, String originalHost) {
		SSLSocketFactory defaultFactory = httpsConn.getSSLSocketFactory();
		httpsConn.setSSLSocketFactory(new SniSSLSocketFactory(defaultFactory, originalHost));

		HostnameVerifier defaultVerifier = httpsConn.getHostnameVerifier();
		httpsConn.setHostnameVerifier((hostname, session) ->
				defaultVerifier.verify(originalHost, session));
	}

	private byte[] readWithLimit(InputStream is) throws IOException {
		byte[] buf = new byte[8192];
		int totalRead = 0;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int bytesRead;
		while ((bytesRead = is.read(buf)) != -1) {
			totalRead += bytesRead;
			if (totalRead > MAX_RESPONSE_BYTES) {
				throw new CustomResponseException("Upstream response exceeds maximum allowed size of " + MAX_RESPONSE_BYTES + " bytes",
						HttpStatus.BAD_GATEWAY);
			}
			out.write(buf, 0, bytesRead);
		}
		return out.toByteArray();
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
