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

package org.entrystore.rest.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "entrystore.auth.cas")
public record CasCustomConfiguration(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("CAS2") CasVersion version,
		Server server,
		@DefaultValue("false") boolean userAutoProvisioning,
		RedirectSuccess redirectSuccess,
		RedirectFailure redirectFailure
) {
	public CasCustomConfiguration {
		if (server == null) server = new Server(null, null);
		if (redirectSuccess == null) redirectSuccess = new RedirectSuccess("/auth/user");
		if (redirectFailure == null) redirectFailure = new RedirectFailure("/auth/user");
		if (enabled && (server.url() == null || server.url().isBlank())) {
			throw new IllegalArgumentException(
					"CAS is enabled (entrystore.auth.cas.enabled=true) but entrystore.auth.cas.server.url is not configured");
		}
	}

	public record Server(String url, String urlLogin) {
		public String resolvedLoginUrl() {
			if (urlLogin != null) return urlLogin;
			if (url == null) return null;
			return url.endsWith("/") ? url + "login" : url + "/login";
		}
	}

	public record RedirectSuccess(String url) {}
	public record RedirectFailure(String url) {}
}
