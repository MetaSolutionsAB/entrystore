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

import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectUrlValidator {

	private final RepositoryManagerImpl repositoryManager;

	private List<String> permittedBaseUrls;

	@PostConstruct
	void init() {
		String repoUrl = repositoryManager.getRepositoryURL().toString();
		List<String> urls = new ArrayList<>();
		if (StringUtils.countMatches(repoUrl, '/') > 2) {
			urls.add(repoUrl.substring(0, StringUtils.ordinalIndexOf(repoUrl, "/", 3) + 1));
		} else {
			log.warn("Base URL is potentially misconfigured: {}", repoUrl);
		}
		urls.addAll(repositoryManager.getConfiguration().getStringList(Settings.AUTH_PERMITTED_REDIRECTS, new ArrayList<>()));
		permittedBaseUrls = List.copyOf(urls);
	}

	public boolean isPermitted(@NonNull String redirectUrl) {
		URI redirect;
		try {
			redirect = new URI(redirectUrl);
		} catch (URISyntaxException e) {
			return false;
		}
		if (redirect.getUserInfo() != null) {
			return false;
		}
		for (String base : permittedBaseUrls) {
			try {
				URI baseUri = new URI(base.endsWith("/") ? base : base + "/");
				if (Objects.equals(redirect.getScheme(), baseUri.getScheme())
						&& Objects.equals(redirect.getHost(), baseUri.getHost())
						&& redirect.getPort() == baseUri.getPort()
						&& redirect.getPath() != null
						&& redirect.getPath().startsWith(baseUri.getPath())) {
					return true;
				}
			} catch (URISyntaxException e) {
				log.warn("Skipping malformed permitted base URL '{}': {}", base, e.getMessage());
			}
		}
		return false;
	}

}
