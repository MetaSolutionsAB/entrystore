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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.dto.ProxyResponse;
import org.entrystore.rest.springboot.service.ProxyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProxyController {

	private final ProxyService proxyService;

	@Operation(summary = "Proxy a request to an external URL", description = "Fetches the content of the given URL and returns it to the client. Guest users can only access whitelisted hosts.")
	@GetMapping("/proxy")
	public ResponseEntity<byte[]> proxyGlobal(
			@RequestParam("url") String url,
			@RequestHeader(value = "Accept", required = false, defaultValue = "*/*") String acceptHeader) {

		proxyService.validateUrl(url);
		String host = proxyService.extractHost(url);
		proxyService.validateGlobalAccess(host);
		return doProxy(url, acceptHeader);
	}

	@Operation(summary = "Proxy a request to an external URL within a context scope", description = "Fetches the content of the given URL, scoped to the given context. Requires ReadResource access on the context.")
	@GetMapping("/{context-id}/proxy")
	public ResponseEntity<byte[]> proxyContext(
			@PathVariable("context-id") String contextId,
			@RequestParam("url") String url,
			@RequestHeader(value = "Accept", required = false, defaultValue = "*/*") String acceptHeader) {

		proxyService.validateUrl(url);
		proxyService.validateContextAccess(contextId);
		return doProxy(url, acceptHeader);
	}

	private ResponseEntity<byte[]> doProxy(String url, String acceptHeader) {
		log.debug("Received proxy request for {}", url);
		ProxyResponse response = proxyService.fetchUrl(url, acceptHeader);

		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Security-Policy", "script-src 'none'; form-action 'none';"); // XSS and SSRF protection
		if (response.contentType() != null) {
			headers.set(HttpHeaders.CONTENT_TYPE, response.contentType());
		}

		return new ResponseEntity<>(response.body(), headers, HttpStatusCode.valueOf(response.statusCode()));
	}
}
