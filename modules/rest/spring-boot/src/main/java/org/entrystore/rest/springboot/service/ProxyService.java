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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.configuration.ProxyProperties;
import org.entrystore.rest.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.security.SsrfSafeHttpClient;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {

	private final PrincipalManager principalManager;
	private final ContextService contextService;
	private final SsrfValidator ssrfValidator;
	private final SsrfSafeHttpClient ssrfSafeHttpClient;

	private final ProxyProperties proxyProperties;

	private Set<String> whitelistAnon;

	@PostConstruct
	void init() {
		whitelistAnon = ssrfValidator.loadHostSet(Settings.PROXY_WHITELIST_ANONYMOUS);
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

	public ProxyResponse fetchUrl(SsrfValidator.ValidatedTarget target, String acceptHeader, boolean enforceAnonWhitelist) {
		Map<String, String> requestHeaders = acceptHeader != null ? Map.of("Accept", acceptHeader) : Map.of();
		return ssrfSafeHttpClient.execute(target, "GET", requestHeaders,
				location -> validateRedirectTarget(location, enforceAnonWhitelist),
				(status, conn) -> {
					String contentType = conn.getContentType();
					byte[] body;
					try (InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream()) {
						body = (is != null) ? readWithLimit(is) : new byte[0];
					}
					return new ProxyResponse(status, contentType, body);
				});
	}

	/**
	 * Re-validates a resolved redirect location. SSRF re-validation
	 * ({@link SsrfValidator#validateForProxy(String)}) runs on every hop because the redirect
	 * target may differ from the origin. When {@code enforceAnonWhitelist} is set (global
	 * {@code /proxy} path), the guest anon-whitelist check is re-applied to the redirect host as
	 * well, so a whitelisted upstream cannot redirect a guest to a non-whitelisted host. The
	 * context-scoped path passes {@code false} — it is gated by a one-time context ACL check, not
	 * the anon whitelist.
	 */
	SsrfValidator.ValidatedTarget validateRedirectTarget(String resolvedLocation, boolean enforceAnonWhitelist) {
		log.debug("Request redirected to {}", resolvedLocation);
		SsrfValidator.ValidatedTarget next = ssrfValidator.validateForProxy(resolvedLocation);
		if (enforceAnonWhitelist) {
			validateGlobalAccess(next.host());
		}
		return next;
	}

	private byte[] readWithLimit(InputStream is) throws IOException {
		// long rather than int: the cap is operator-configurable and may legitimately exceed 2 GiB, at
		// which point an int accumulator would overflow negative and stop enforcing the limit entirely.
		long maxResponseBytes = proxyProperties.maxResponseSize().toBytes();
		byte[] buf = new byte[8192];
		long totalRead = 0;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int bytesRead;
		while ((bytesRead = is.read(buf)) != -1) {
			totalRead += bytesRead;
			if (totalRead > maxResponseBytes) {
				throw new CustomResponseException("Upstream response exceeds maximum allowed size of " + maxResponseBytes + " bytes",
						HttpStatus.BAD_GATEWAY);
			}
			out.write(buf, 0, bytesRead);
		}
		return out.toByteArray();
	}
}
