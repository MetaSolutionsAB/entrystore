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

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.configuration.IndexedListSettings;
import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfSafeHttpClient;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

	private final PrincipalManager principalManager;
	private final ContextService contextService;
	private final SsrfValidator ssrfValidator;
	private final SsrfSafeHttpClient ssrfSafeHttpClient;
	private final ProxyProperties proxyProperties;
	private final Environment environment;

	/**
	 * Entity headers copied from the upstream response besides Content-Type and Content-Length: those 5.x
	 * passed through with the upstream entity, except Content-Location and Content-MD5.
	 */
	private static final List<String> PASSED_THROUGH_HEADERS = List.of(HttpHeaders.CONTENT_ENCODING,
			HttpHeaders.CONTENT_LANGUAGE, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.LAST_MODIFIED,
			HttpHeaders.ETAG, HttpHeaders.EXPIRES);

	private Set<String> whitelistAnon;

	@PostConstruct
	void init() {
		whitelistAnon = IndexedListSettings.readHosts(environment, Settings.PROXY_WHITELIST_ANONYMOUS);
		if (!whitelistAnon.isEmpty()) {
			log.info("Proxy whitelist for guest users initialized with following domains: {}; Requests to other domains require authentication",
					String.join(", ", whitelistAnon));
		} else {
			log.info("No domains provided for proxy whitelist; only authenticated users are allowed to perform proxy requests");
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

	void setWhitelistAnon(Set<String> whitelistAnon) {
		this.whitelistAnon = whitelistAnon;
	}

	/**
	 * Fetches {@code target} and streams the upstream status, Content-Type, Content-Length, the headers in
	 * {@link #PASSED_THROUGH_HEADERS} and the body to {@code response}, so heap use does not grow with the
	 * body size. The request thread is held for the whole transfer, as it was while the body was buffered.
	 *
	 * <p>With {@code entrystore.proxy.max-response-size} set, an upstream announcing a larger Content-Length
	 * is answered 502 before anything is sent. A body without Content-Length that grows past the cap is cut
	 * off: before the response is committed it is answered 502, afterwards the connection is aborted.
	 *
	 * <p>With {@code headersOnly}, for a HEAD request, the upstream is still fetched with GET, as in 5.x, but its
	 * body is never read.
	 */
	public void proxy(SsrfValidator.ValidatedTarget target, String acceptHeader, boolean headersOnly,
					  HttpServletResponse response) {
		Map<String, String> requestHeaders = acceptHeader != null ? Map.of("Accept", acceptHeader) : Map.of();
		ssrfSafeHttpClient.execute(target, "GET", requestHeaders,
				this::validateRedirectTarget,
				(status, conn) -> {
					streamResponse(status, conn, headersOnly, response);
					return null;
				});
	}

	private void streamResponse(int status, HttpURLConnection conn, boolean headersOnly, HttpServletResponse response)
			throws IOException {
		if (status < 100) {
			// HttpURLConnection reports -1 for a response that is not valid HTTP.
			throw new CustomResponseException("Proxy request failed", HttpStatus.BAD_GATEWAY);
		}
		// With Transfer-Encoding, Content-Length must be ignored (RFC 9112, section 6.3).
		HttpHeaders upstream = upstreamHeaders(conn);
		long contentLength = upstream.getOrEmpty(HttpHeaders.TRANSFER_ENCODING).isEmpty() ? conn.getContentLengthLong() : -1;
		if (proxyProperties.isResponseSizeLimited() && contentLength > proxyProperties.maxResponseSize().toBytes()) {
			throw responseTooLarge();
		}
		Set<String> hopByHop = connectionNominatedHeaders(upstream);
		response.setStatus(status);
		response.setHeader("Content-Security-Policy", "script-src 'none'; form-action 'none';"); // XSS and SSRF protection
		if (conn.getContentType() != null && !hopByHop.contains(HttpHeaders.CONTENT_TYPE)) {
			response.setContentType(conn.getContentType());
		}
		if (contentLength >= 0 && !hopByHop.contains(HttpHeaders.CONTENT_LENGTH)) {
			response.setContentLengthLong(contentLength);
		}
		for (String name : PASSED_THROUGH_HEADERS) {
			if (!hopByHop.contains(name)) {
				upstream.getOrEmpty(name).forEach(value -> response.addHeader(name, value));
			}
		}
		if (headersOnly) {
			return;
		}
		try (InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
			if (in != null) {
				copyWithLimit(in, response.getOutputStream());
			}
		} catch (IOException | RuntimeException e) {
			if (!response.isCommitted()) {
				// The error response that follows must carry neither the upstream's entity headers nor its bytes.
				response.resetBuffer();
				response.setContentLengthLong(-1);
				PASSED_THROUGH_HEADERS.forEach(name -> response.setHeader(name, null));
			}
			throw e;
		}
	}

	/**
	 * The header names the upstream's Connection headers nominate as hop-by-hop (RFC 9110, section 7.6.1), which
	 * must not be forwarded, whichever header they name.
	 */
	private static Set<String> connectionNominatedHeaders(HttpHeaders upstream) {
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		upstream.getOrEmpty(HttpHeaders.CONNECTION).stream()
				.flatMap(value -> Arrays.stream(value.split(",")))
				.map(String::trim)
				.filter(token -> !token.isEmpty())
				.forEach(names::add);
		return names;
	}

	/**
	 * The upstream's header fields in the order received, with names matched case-insensitively. Repeated fields
	 * keep their wire order whatever their spelling, which {@code getHeaderFields()} does not: it groups values by
	 * the exact spelling of the name. Index 0 is the status line, which has no name.
	 */
	private static HttpHeaders upstreamHeaders(HttpURLConnection conn) {
		HttpHeaders headers = new HttpHeaders();
		for (int i = 0; ; i++) {
			String name = conn.getHeaderFieldKey(i);
			String value = conn.getHeaderField(i);
			if (name == null && value == null) {
				return headers;
			}
			if (name != null && value != null) {
				headers.add(name, value);
			}
		}
	}

	private void copyWithLimit(InputStream in, OutputStream out) throws IOException {
		boolean limited = proxyProperties.isResponseSizeLimited();
		long maxBytes = proxyProperties.maxResponseSize().toBytes();
		byte[] buf = new byte[8192];
		long total = 0;
		int read;
		while ((read = in.read(buf)) != -1) {
			total += read;
			if (limited && total > maxBytes) {
				throw responseTooLarge();
			}
			try {
				out.write(buf, 0, read);
			} catch (IOException e) {
				// Unchecked, so SsrfSafeHttpClient does not report the client's disconnect as an upstream failure.
				throw new CustomResponseException("Proxy client went away", HttpStatus.BAD_GATEWAY, e);
			}
		}
	}

	private CustomResponseException responseTooLarge() {
		return new CustomResponseException("Upstream response exceeds maximum allowed size of "
				+ proxyProperties.maxResponseSize().toBytes() + " bytes", HttpStatus.BAD_GATEWAY);
	}

	/**
	 * Re-validates a resolved redirect location: SSRF validation
	 * ({@link SsrfValidator#validateForProxy(String)}) and the guest anon-whitelist, on every hop, so that a
	 * whitelisted upstream cannot redirect a guest elsewhere. Both proxy routes enforce the whitelist, as 5.x did.
	 */
	SsrfValidator.ValidatedTarget validateRedirectTarget(String resolvedLocation) {
		log.debug("Request redirected to {}", resolvedLocation);
		SsrfValidator.ValidatedTarget next = ssrfValidator.validateForProxy(resolvedLocation);
		validateGlobalAccess(next.host());
		return next;
	}

	/**
	 * Sends an SSRF-guarded DELETE to a remote resource, re-validating every redirect hop. Used for
	 * {@code DELETE /{ctx}/resource/{id}?proxy=true} on link entries; the caller performs the ACL check. Unlike
	 * {@link #proxy}, the URL is validated here because the caller holds a stored resource URI, not a
	 * client-supplied one already resolved by the controller. A non-2xx upstream answer is reported as 502 without
	 * echoing the upstream body.
	 */
	public void deleteUrl(String url) {
		SsrfValidator.ValidatedTarget target = ssrfValidator.validateForDelete(url);
		ssrfSafeHttpClient.execute(target, "DELETE", Map.of(),
				location -> {
					log.info("DELETE request redirected to {}", location);
					return ssrfValidator.validateForDelete(location);
				},
				(status, conn) -> {
					if (status >= 200 && status < 300) {
						return null;
					}
					// Suppress upstream body — may leak internal details.
					throw new CustomResponseException("Delete request received an error response (status " + status + ")",
							HttpStatus.BAD_GATEWAY);
				});
	}
}
