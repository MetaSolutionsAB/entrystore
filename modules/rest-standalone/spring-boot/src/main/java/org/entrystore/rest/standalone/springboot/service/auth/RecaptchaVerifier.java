/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

package org.entrystore.rest.standalone.springboot.service.auth;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies the validity of a reCaptcha user response token.
 * <p>
 * Uses reCaptcha API 2.0.
 *
 * @author Hannes Ebner
 */
public class RecaptchaVerifier {

	private static final Logger log = LoggerFactory.getLogger(RecaptchaVerifier.class);

	//@Value("${entrystore.auth.recaptcha.url}")
	private final String url;
	private final String secret;

	public RecaptchaVerifier(String url, String secret) {
		if (url == null || secret == null) {
			throw new IllegalArgumentException("reCaptcha url and secret must not be null");
		}
		this.url = url;
		this.secret = secret;
	}

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

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("secret", secret);
		params.add("response", rcResponseV2);
		if (userIP != null) {
			params.add("remoteip", userIP);
		}

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
		RestTemplate restTemplate = new RestTemplate();
		ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

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
