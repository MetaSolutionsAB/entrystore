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

package org.entrystore.rest.springboot.security;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.rest.springboot.configuration.OidcCustomConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aborts startup when {@code entrystore.auth.oidc.default-provider} or an
 * {@code entrystore.auth.oidc.provider.{id}} entry names a provider id without a matching
 * {@code spring.security.oauth2.client.registration.{id}} entry. Every id both sides use is known
 * at startup, and such a mismatch breaks logins at runtime — a {@code default-provider} typo turns
 * every hint-less {@code /auth/oidc} into a 500 on an app that reports healthy — so it must fail
 * here, naming the offending key, rather than be discovered from login traffic. The request-time
 * check in {@code AuthController.startOidcLogin} remains as a backstop.
 */
@Component
@Conditional(OidcLoginSuccessHandler.OidcEnabledCondition.class)
@RequiredArgsConstructor
public class OidcProviderRegistrationValidator implements InitializingBean {

	private final OidcCustomConfiguration oidcConfiguration;
	// Optional: the bean exists only when spring.security.oauth2.client registrations are configured.
	private final Optional<ClientRegistrationRepository> clientRegistrationRepository;

	@Override
	public void afterPropertiesSet() {
		List<String> unknown = new ArrayList<>();
		String defaultProvider = oidcConfiguration.defaultProvider();
		if (StringUtils.isNotBlank(defaultProvider) && !isRegistered(defaultProvider)) {
			unknown.add("entrystore.auth.oidc.default-provider=" + defaultProvider);
		}
		for (String providerId : oidcConfiguration.provider().keySet()) {
			if (!isRegistered(providerId)) {
				unknown.add("entrystore.auth.oidc.provider." + providerId + ".*");
			}
		}
		if (!unknown.isEmpty()) {
			throw new IllegalStateException("EntryStore startup aborted: OIDC configuration references "
					+ "provider ids without a matching spring.security.oauth2.client.registration entry: "
					+ unknown + ". Fix the key(s) or add the missing client registration(s).");
		}
	}

	private boolean isRegistered(String providerId) {
		return clientRegistrationRepository
				.map(registrations -> registrations.findByRegistrationId(providerId) != null)
				.orElse(false);
	}
}
