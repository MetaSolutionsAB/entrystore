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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class RecaptchaVerifier {

	private static final String RECAPTCHA_URL_DEFAULT = "https://www.google.com/recaptcha/api/siteverify";

	private final RepositoryManagerImpl repositoryManager;
	private final Config esConfig;
	private final RestTemplate restTemplate;

	private static String url;
	private static String secret;

	@PostConstruct
	public void init() {
		url = esConfig.getString(Settings.AUTH_RECAPTCHA_URL, RECAPTCHA_URL_DEFAULT);
		secret = esConfig.getString(Settings.AUTH_RECAPTCHA_PRIVATE_KEY);
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
