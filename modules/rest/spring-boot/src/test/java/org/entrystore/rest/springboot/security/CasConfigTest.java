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

import org.apereo.cas.client.ssl.HttpURLConnectionFactory;
import org.apereo.cas.client.validation.AbstractUrlBasedTicketValidator;
import org.apereo.cas.client.validation.AssertionImpl;
import org.apereo.cas.client.validation.Cas10TicketValidator;
import org.apereo.cas.client.validation.Cas20ServiceTicketValidator;
import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidator;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.configuration.CasVersion;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.cas.authentication.CasServiceTicketAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.HttpsURLConnection;
import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CasConfigTest {

	@Mock
	private ESUserDetailsService userDetailsService;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private AuthTokenCookies authTokenCookies;

	@Test
	void relaxedEnabledSettingCreatesTheCasBeansButNoAuthenticationProviderBean() throws Exception {
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("https://sp.entrystore.example/").toURL());

		new ApplicationContextRunner()
				.withUserConfiguration(CasBinding.class, CasConfig.class)
				.withBean(ESUserDetailsService.class, () -> userDetailsService)
				.withBean(PrincipalManager.class, () -> principalManager)
				.withBean(RepositoryManagerImpl.class, () -> repositoryManager)
				.withBean(ErrorResponseWriter.class, () -> mock(ErrorResponseWriter.class))
				.withBean(AuthTokenCookies.class, () -> authTokenCookies)
				.withPropertyValues("entrystore.auth.cas.enabled=yes",
						"entrystore.auth.cas.server.url=https://cas.example.org/cas")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.getBean(CasCustomConfiguration.class).enabled());
					assertNotNull(context.getBean(ServiceProperties.class));
					assertNotNull(context.getBean(TicketValidator.class));
					assertNotNull(context.getBean(CasLoginSuccessHandler.class));
					// A single AuthenticationProvider bean would become the global AuthenticationManager's only
					// provider and break password and HTTP Basic login
					assertTrue(context.getBeansOfType(AuthenticationProvider.class).isEmpty());
				});
	}

	@Test
	void casProviderAuthenticatesAValidTicketAsAUserNamedByTheCasLogin() throws Exception {
		var serviceProperties = new ServiceProperties();
		serviceProperties.setService("https://sp.entrystore.example/auth/cas");
		TicketValidator ticketValidator = mock(TicketValidator.class);
		when(ticketValidator.validate("ST-1", "https://sp.entrystore.example/auth/cas"))
				.thenReturn(new AssertionImpl("casuser"));

		var authentication = CasConfig.casAuthenticationProvider(serviceProperties, ticketValidator)
				.authenticate(CasServiceTicketAuthenticationToken.stateful("ST-1"));

		var principal = assertInstanceOf(User.class, assertInstanceOf(CasAuthenticationToken.class, authentication)
				.getPrincipal());
		assertEquals("casuser", principal.getUsername());
		assertEquals(Set.of("ROLE_USER"), AuthorityUtils.authorityListToSet(principal.getAuthorities()));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CasCustomConfiguration.class)
	static class CasBinding {}

	private CasConfig configWithVersion(CasVersion version) {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var casConfig = new CasCustomConfiguration(true, version, server, false, null, null);
		// errorResponseWriter is null: these tests only drive casTicketValidator(), which never reaches
		// the success handler that consumes it.
		CasConfig config = new CasConfig(casConfig, userDetailsService, principalManager, repositoryManager, null);
		// @Value-injected field — set via reflection since we're not using Spring context in this unit test.
		ReflectionTestUtils.setField(config, "disableSslVerification", false);
		return config;
	}

	@Test
	void cas1VersionCreatesCas10TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS1).casTicketValidator();
		assertInstanceOf(Cas10TicketValidator.class, validator);
	}

	@Test
	void cas2VersionCreatesCas20TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS2).casTicketValidator();
		assertInstanceOf(Cas20ServiceTicketValidator.class, validator);
	}

	@Test
	void cas3VersionCreatesCas30TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS3).casTicketValidator();
		assertInstanceOf(Cas30ServiceTicketValidator.class, validator);
	}

	@Test
	void sslVerificationDisabledInstallsTrustAllFactoryAndPinsTimeouts() throws Exception {
		CasConfig cfg = configWithVersion(CasVersion.CAS2);
		ReflectionTestUtils.setField(cfg, "disableSslVerification", true);

		var validator = (AbstractUrlBasedTicketValidator) cfg.casTicketValidator();
		var factory = (HttpURLConnectionFactory) ReflectionTestUtils.getField(validator, "urlConnectionFactory");
		var conn1 = (HttpsURLConnection) factory.buildHttpURLConnection(
				URI.create("https://example.invalid").toURL().openConnection());
		var conn2 = (HttpsURLConnection) factory.buildHttpURLConnection(
				URI.create("https://example.invalid").toURL().openConnection());

		assertNotSame(HttpsURLConnection.getDefaultSSLSocketFactory(), conn1.getSSLSocketFactory());
		assertTrue(conn1.getHostnameVerifier().verify("any.host", null));
		assertEquals(5_000, conn1.getConnectTimeout());
		assertEquals(10_000, conn1.getReadTimeout());
		// Factory is built once at bean init and reused; regression would re-allocate per request.
		assertSame(conn1.getSSLSocketFactory(), conn2.getSSLSocketFactory());
	}
}
