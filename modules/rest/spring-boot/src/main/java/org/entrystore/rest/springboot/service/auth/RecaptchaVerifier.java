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

package org.entrystore.rest.springboot.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.rest.springboot.model.dto.RecaptchaSiteVerifyResponse;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownHttpStatusCodeException;

import java.util.Set;

/**
 * Verifies the validity of a reCaptcha user response token.
 * <p>
 * Uses reCaptcha API 2.0.
 *
 * @author Hannes Ebner
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecaptchaVerifier {

	/** Codes that indicate the deployment's own configuration is wrong, not the caller's token. */
	private static final Set<String> DEPLOYMENT_ERROR_CODES =
		Set.of("missing-input-secret", "invalid-input-secret", "bad-request");

	@Qualifier("recaptchaRestClient")
	private final RestClient recaptchaRestClient;

	// Read through the same Spring property channel as the AuthService enable-gate
	// (@Value("${entrystore.auth.recaptcha.private-key}") + "${entrystore.auth.recaptcha:off}"). Reading
	// the secret via the legacy Config bean instead would diverge: EntryStoreConfiguration only copies
	// Spring keys literally starting with "entrystore." into Config, so a key supplied only as an env var
	// (ENTRYSTORE_AUTH_RECAPTCHA_PRIVATE_KEY) would pass the gate yet leave this secret null.
	@Value("${entrystore.auth.recaptcha.url:https://www.google.com/recaptcha/api/siteverify}")
	private String url;

	@Value("${entrystore.auth.recaptcha.private-key:#{null}}")
	private String secret;

	/**
	 * Verifies a user response token using reCaptcha 2.0 API.
	 *
	 * @param rcResponseV2 The user response token (usually contained in g-recaptcha-response POST parameter).
	 * @param userIP       The user's IP-address. Optional, i.e., may be null.
	 * @return True if the user response token has been successfully verified, false otherwise.
	 */
	public boolean verify(String rcResponseV2, String userIP) {
		// The secret is a request parameter, so never build or log a URL carrying it — that writes the
		// deployment's reCaptcha private key into the log on every verification. Log the endpoint alone.
		log.debug("Verifying reCaptcha response via {}", url);

		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("secret", secret);
		params.add("response", rcResponseV2);
		if (userIP != null) {
			params.add("remoteip", userIP);
		}

		ResponseEntity<RecaptchaSiteVerifyResponse> response;
		try {
			response = recaptchaRestClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(params)
				.retrieve()
				.toEntity(RecaptchaSiteVerifyResponse.class);
		} catch (ResourceAccessException | HttpServerErrorException | UnknownHttpStatusCodeException e) {
			throw new CustomResponseException(
				"reCaptcha verifier is currently unavailable. Please try again later.",
				HttpStatus.SERVICE_UNAVAILABLE, e);
		} catch (HttpClientErrorException e) {
			// 4xx is deliberately not remapped: a misconfigured secret must surface, not be masked as a
			// transient 503. Rethrown explicitly because the RestClientException catch below would
			// otherwise swallow it into a rejected captcha.
			throw e;
		} catch (RestClientException e) {
			// The body is decoded inside toEntity, so a 2xx whose body is not this record — an
			// intercepting proxy's text/html page, a truncated reply — surfaces here as
			// UnknownContentTypeException or a bare RestClientException. Neither is a server fault of
			// ours, and treating it as "not verified" both fails closed and keeps the deterministic
			// statuses this verifier was hardened for; letting it propagate answers 500 on an
			// unauthenticated endpoint instead.
			log.warn("Could not read the reCaptcha siteverify response; treating the token as unverified", e);
			return false;
		}

		RecaptchaSiteVerifyResponse result = response.getBody();
		if (!response.getStatusCode().is2xxSuccessful() || result == null) {
			return false;
		}

		if (!result.success() && !result.errorCodes().isEmpty()) {
			// A secret-related code means the deployment is misconfigured and *every* sign-up will fail,
			// so it belongs above DEBUG: at the default level a total sign-up outage would otherwise be
			// indistinguishable from ordinary users mistyping captchas. A per-user code such as
			// timeout-or-duplicate stays at DEBUG.
			if (result.errorCodes().stream().anyMatch(DEPLOYMENT_ERROR_CODES::contains)) {
				log.warn("reCaptcha rejected the request for a configuration reason, error codes: {}",
					result.errorCodes());
			} else {
				log.debug("reCaptcha verification rejected, error codes: {}", result.errorCodes());
			}
		}
		return result.success();
	}

}
