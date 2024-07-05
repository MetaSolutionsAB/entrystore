/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.auth;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
* @author Hannes Ebner
*/
@lombok.Getter
@lombok.Setter
public class SignupInfo {

	private static final Logger log = LoggerFactory.getLogger(SignupInfo.class);

	private String firstName;

	private String lastName;

	private String email;

	private String saltedHashedPassword;

	private Date expirationDate;

	private String urlSuccess;

	private String urlFailure;

	private Map<String, String> customProperties;

	private RepositoryManager rm;

	private static List<String> permittedBaseUrls;

	public SignupInfo(RepositoryManager rm) {
		this.rm = rm;
		if (permittedBaseUrls == null) {
			String repoUrl = rm.getRepositoryURL().toString();
			permittedBaseUrls = new ArrayList<>();
			if (StringUtils.countMatches(repoUrl, '/') > 2) {
				permittedBaseUrls.add(repoUrl.substring(0, StringUtils.ordinalIndexOf(repoUrl, "/", 3) + 1));
			} else {
				log.warn("Base URL is potentially misconfigured: {}", repoUrl);
			}
			permittedBaseUrls.addAll(rm.getConfiguration().getStringList(Settings.AUTH_PERMITTED_REDIRECTS, new ArrayList<>()));
		}
	}

	public void setUrlSuccess(String urlSuccess) {
		if (urlSuccess != null) {
			if (isPermittedRedirectUrl(urlSuccess)) {
				this.urlSuccess = urlSuccess;
			} else {
				log.warn("Redirect URL (success) is not permitted and will be ignored: {}", urlSuccess);
			}
		}
	}

	public void setUrlFailure(String urlFailure) {
		if (urlFailure != null) {
			if (isPermittedRedirectUrl(urlFailure)) {
				this.urlFailure = urlFailure;
			} else {
				log.warn("Redirect URL (failure) is not permitted and will be ignored: {}", urlFailure);
			}
		}
	}

	public void setEmail(@NonNull String email) {
		// we have to store it in lower case only to avoid problems with different cases in
		// different steps of the process (if the user provides inconsistent information)
		this.email = email.toLowerCase();
	}

	private boolean isPermittedRedirectUrl(@NonNull String redirectUrl) {
		for (String base : permittedBaseUrls) {
			if (!base.endsWith("/")) {
				base += "/";
			}
			if (redirectUrl.startsWith(base)) {
				return true;
			}
		}
		return false;
	}

}