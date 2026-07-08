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
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.UnknownHttpStatusCodeException;

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
		StringBuilder reCaptchaUrl = new StringBuilder().
			append(url).
			append("?secret=").append(secret).
			append("&response=").append(rcResponseV2);
		if (userIP != null) {
			reCaptchaUrl.append("&remoteip=").append(userIP);
		}

		log.debug("reCaptcha URL: {}", reCaptchaUrl);

		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("secret", secret);
		params.add("response", rcResponseV2);
		if (userIP != null) {
			params.add("remoteip", userIP);
		}

		ResponseEntity<String> response;
		try {
			response = recaptchaRestClient.post()
				.uri(url)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(params)
				.retrieve()
				.toEntity(String.class);
		} catch (ResourceAccessException | HttpServerErrorException | UnknownHttpStatusCodeException e) {
			// 4xx (HttpClientErrorException) is deliberately not remapped: a misconfigured
			// secret must surface, not be masked as a transient 503.
			throw new CustomResponseException(
				"reCaptcha verifier is currently unavailable. Please try again later.",
				HttpStatus.SERVICE_UNAVAILABLE, e);
		}

		if (!response.getStatusCode().is2xxSuccessful()) {
			return false;
		}

		try {
			JSONObject result = new JSONObject(response.getBody());
			if (result.has("success")) {
				return result.getBoolean("success");
			}
		} catch (JSONException e) {
			log.debug(e.getMessage());
			return false;
		}

		return false;
	}

}
