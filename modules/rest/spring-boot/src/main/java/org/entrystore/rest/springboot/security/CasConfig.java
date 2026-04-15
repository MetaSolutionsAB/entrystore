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
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.ssl.HttpsURLConnectionFactory;
import org.apereo.cas.client.validation.AbstractUrlBasedTicketValidator;
import org.apereo.cas.client.validation.Cas10TicketValidator;
import org.apereo.cas.client.validation.Cas20ServiceTicketValidator;
import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidator;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "entrystore.auth.cas.enabled", havingValue = "true")
public class CasConfig {

	// Backchannel HTTP timeouts for CAS ticket validation — prevents thread pool exhaustion
	// if the CAS server becomes slow or unresponsive during login.
	private static final int CAS_CONNECT_TIMEOUT_MS = 5_000;
	private static final int CAS_READ_TIMEOUT_MS = 10_000;

	private final CasCustomConfiguration casConfiguration;
	private final ESUserDetailsService userDetailsService;
	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;

	@Bean
	public ServiceProperties casServiceProperties() {
		ServiceProperties sp = new ServiceProperties();
		String baseUrl = repositoryManager.getRepositoryURL().toExternalForm();
		if (!baseUrl.endsWith("/")) {
			baseUrl += "/";
		}
		sp.setService(baseUrl + "auth/cas");
		sp.setSendRenew(false);
		return sp;
	}

	@Bean
	public TicketValidator casTicketValidator() {
		String casServerUrl = casConfiguration.server().url();
		AbstractUrlBasedTicketValidator validator = switch (casConfiguration.version().toLowerCase()) {
			case "cas1" -> new Cas10TicketValidator(casServerUrl);
			case "cas2" -> new Cas20ServiceTicketValidator(casServerUrl);
			case "cas3" -> new Cas30ServiceTicketValidator(casServerUrl);
			default -> throw new IllegalStateException(
					"Unsupported CAS version '" + casConfiguration.version() +
							"'. Supported values: cas1, cas2, cas3.");
		};
		var defaultFactory = new HttpsURLConnectionFactory();
		validator.setURLConnectionFactory(urlConnection -> {
			var conn = defaultFactory.buildHttpURLConnection(urlConnection);
			conn.setConnectTimeout(CAS_CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(CAS_READ_TIMEOUT_MS);
			return conn;
		});
		return validator;
	}

	@Bean
	public CasAuthenticationProvider casAuthenticationProvider() {
		CasAuthenticationProvider provider = new CasAuthenticationProvider();
		provider.setServiceProperties(casServiceProperties());
		provider.setTicketValidator(casTicketValidator());
		provider.setAuthenticationUserDetailsService(token ->
				new org.springframework.security.core.userdetails.User(
						token.getName(),
						"N/A",
						List.of(new SimpleGrantedAuthority("ROLE_USER"))));
		// Random key per JVM — used by CasAuthenticationToken for internal hash-based integrity checks
		provider.setKey(UUID.randomUUID().toString());
		return provider;
	}

	@Bean
	public CasLoginSuccessHandler casLoginSuccessHandler() {
		return new CasLoginSuccessHandler(userDetailsService, principalManager, casConfiguration);
	}
}
