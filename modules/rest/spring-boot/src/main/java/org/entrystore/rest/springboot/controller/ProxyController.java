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

package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.security.SsrfValidator;
import org.entrystore.rest.springboot.service.ProxyService;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProxyController {

	private final ProxyService proxyService;
	private final SsrfValidator ssrfValidator;

	@Operation(summary = "Proxy a request to an external URL", description = "Fetches the content of the given URL and returns it to the client. Guest users can only access whitelisted hosts.")
	@GetMapping("/proxy")
	public void proxyGlobal(
			@RequestParam("url") String url,
			@RequestHeader(value = "Accept", required = false, defaultValue = "*/*") String acceptHeader,
			HttpMethod method,
			HttpServletResponse response) {

		// Order matches ENTRYSTORE-949: cheap URL parse + scheme/userinfo check before auth,
		// then guest-whitelist auth, then DNS resolution.
		URI uri = ssrfValidator.parseAndValidateUrl(url);
		proxyService.validateGlobalAccess(uri.getHost().toLowerCase(Locale.ROOT));
		SsrfValidator.ValidatedTarget target = ssrfValidator.resolveForProxy(uri);
		doProxy(target, acceptHeader, method, response);
	}

	@Operation(summary = "Proxy a request to an external URL within a context scope", description = "Fetches the content of the given URL, scoped to the given context. Requires ReadResource access on the context; guest users can only access whitelisted hosts.")
	@GetMapping("/{context-id}/proxy")
	public void proxyContext(
			@PathVariable("context-id") String contextId,
			@RequestParam("url") String url,
			@RequestHeader(value = "Accept", required = false, defaultValue = "*/*") String acceptHeader,
			HttpMethod method,
			HttpServletResponse response) {

		// As in 5.x, the context ACL check comes on top of the guest whitelist, it does not replace it.
		URI uri = ssrfValidator.parseAndValidateUrl(url);
		proxyService.validateContextAccess(contextId);
		proxyService.validateGlobalAccess(uri.getHost().toLowerCase(Locale.ROOT));
		SsrfValidator.ValidatedTarget target = ssrfValidator.resolveForProxy(uri);
		doProxy(target, acceptHeader, method, response);
	}

	/** HEAD reaches these GET handlers too; it gets the upstream's headers without its body being read. */
	private void doProxy(SsrfValidator.ValidatedTarget target, String acceptHeader, HttpMethod method,
						 HttpServletResponse response) {
		log.debug("Received proxy request for {}", target.uri());
		proxyService.proxy(target, acceptHeader, HttpMethod.HEAD.equals(method), response);
	}
}
