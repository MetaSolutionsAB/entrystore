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

package org.entrystore.rest.util;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the validity of a reCaptcha user response token.
 * <p>
 * Uses reCaptcha API 2.0.
 *
 * @author Hannes Ebner
 */
public class RecaptchaVerifier {

	private static final Logger log = LoggerFactory.getLogger(RecaptchaVerifier.class);

	private final String secret;

	public RecaptchaVerifier(String secret) {
		if (secret == null) {
			throw new IllegalArgumentException("reCaptcha secret must not be null");
		}
		this.secret = secret;
	}

	/**
		 * Verifies a user response token using reCaptcha 2.0 API.
	 *
	 * @param userResponse The user response token (usually contained in g-recaptcha-response POST parameter).
	 * @param userIP The user's IP-address. Optional, i.e., may be null.
	 * @return True if the user response token has been successfully verified, false otherwise.
	 */
	public boolean verify(String userResponse, String userIP) {
		if (userResponse == null) {
			throw new IllegalArgumentException("User response token must not be null");
		}

		StringBuilder reCaptchaUrl = new StringBuilder().
				append("https://www.google.com/recaptcha/api/siteverify?secret=").append(secret).
				append("&response=").append(userResponse);
		if (userIP != null) {
			reCaptchaUrl.append("&remoteip=").append(userIP);
		}

		log.debug("reCaptcha URL: {}", reCaptchaUrl);

		Response response = HttpUtil.getResourceFromURL(reCaptchaUrl.toString());
		if (!response.getStatus().isSuccess()) {
			return false;
		}

		try {
			JSONObject result = new JSONObject(response.getEntityAsText());
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
