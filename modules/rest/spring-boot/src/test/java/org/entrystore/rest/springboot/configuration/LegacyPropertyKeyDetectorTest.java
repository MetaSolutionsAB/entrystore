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

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class LegacyPropertyKeyDetectorTest {

	private static final String SAML = "entrystore.auth.saml.";
	private static final String REG = "spring.security.saml2.relyingparty.registration.";
	private final List<String> warnings = new ArrayList<>();

	@Test
	void unchangedMultiIdpConfigurationBindsSpringRegistrationsAndCustomSettings() {
		var env = environment(Map.ofEntries(
				Map.entry("entrystore.auth.saml", "on"),
				Map.entry(SAML + "idps.1", "google"),
				Map.entry(SAML + "idps.2", "other"),
				Map.entry(SAML + "idp.google.metadata.url", "https://idp.example/metadata"),
				Map.entry(SAML + "idp.google.relying-party-id", "unchanged-sp"),
				Map.entry(SAML + "idp.google.redirect-method", "post"),
				Map.entry(SAML + "idp.other.metadata.url", "https://other.example/metadata"),
				Map.entry(SAML + "idp.other.relying-party-id", "other-sp"),
				Map.entry(SAML + "assertion-consumer-service.url", "https://sp.example/store/auth/saml?tenant=acme"),
				Map.entry(SAML + "idp.google.domains.1", "EXAMPLE.com"),
				Map.entry(SAML + "idp.google.domains.2", "example.org"),
				Map.entry(SAML + "idp.google.user-auto-provisioning", "on"),
				Map.entry(SAML + "idp.google.metadata.max-age", "120"),
				Map.entry(SAML + "redirect-domain-whitelist.1", "app.example"),
				Map.entry(SAML + "redirect-domain-whitelist.2", "portal.example")));

		process(env);

		var registrations = registrations(env);
		assertEquals(2, registrations.getRegistration().size());
		var google = registrations.getRegistration().get("google");
		assertEquals("unchanged-sp", google.getEntityId());
		assertEquals("https://idp.example/metadata", google.getAssertingparty().getMetadataUri());
		assertEquals(Saml2MessageBinding.POST, google.getAssertingparty().getSinglesignon().getBinding());
		assertEquals(Boolean.FALSE, google.getAssertingparty().getSinglesignon().getSignRequest());
		assertEquals("https://sp.example/store/auth/saml?tenant=acme&idp=google", google.getAcs().getLocation());
		assertEquals(Saml2MessageBinding.REDIRECT,
				registrations.getRegistration().get("other").getAssertingparty().getSinglesignon().getBinding());
		assertEquals(Boolean.FALSE,
				registrations.getRegistration().get("other").getAssertingparty().getSinglesignon().getSignRequest());
		var saml = saml(env);
		assertTrue(saml.enabled());
		assertEquals(List.of("example.com", "example.org"), saml.idp().get("google").domains());
		assertTrue(saml.idp().get("google").userAutoProvisioning());
		assertEquals(120, saml.idp().get("google").metadata().maxAge());
		assertEquals(List.of("app.example", "portal.example"), saml.redirectDomainWhitelist());
		assertEquals(List.of("*"), saml.idp().get("other").domains());
		assertTrue(warnings.stream().anyMatch(w -> w.contains(REG + "google.entity-id")));
		assertFalse(warnings.stream().anyMatch(w -> w.contains("unchanged-sp") || w.contains("https://")));
	}

	@Test
	void discoversIdpsWithoutEnumeration() {
		var env = environment(Map.of(SAML + "idp.acme.metadata.url", "classpath:metadata.xml"));
		process(env);
		assertEquals("classpath:metadata.xml",
				registrations(env).getRegistration().get("acme").getAssertingparty().getMetadataUri());
		assertNotNull(saml(env).idp().get("acme"));
	}

	@Test
	void mixedCaseIdIsPreserved() {
		var env = environment(Map.of(SAML + "idps.1", "myIdp",
				SAML + "idp.myIdp.metadata.url", "classpath:metadata.xml",
				SAML + "idp.myIdp.domains.1", "example.com"));
		process(env);
		assertEquals(List.of("myIdp"), new ArrayList<>(registrations(env).getRegistration().keySet()));
		assertEquals(List.of("example.com"), saml(env).idp().get("myIdp").domains());
	}

	@Test
	void modernAcsOverridesEvenAnInvalidLegacyUrl() {
		var env = environment(Map.of(
				REG + "acme.assertingparty.metadata-uri", "classpath:metadata.xml",
				REG + "acme.acs.location", "https://sp.example/login/saml2/sso/acme",
				SAML + "assertion-consumer-service.url", "not a URL"));
		process(env);
		assertEquals("https://sp.example/login/saml2/sso/acme",
				registrations(env).getRegistration().get("acme").getAcs().getLocation());
	}

	@Test
	void singleIdpUsesStableDefaultAndPreservesPolicies() {
		var env = environment(Map.of(
				SAML + "idp-metadata.url", "classpath:metadata.xml",
				SAML + "relying-party-id", "old-sp",
				SAML + "idp-metadata.max-age", "120",
				SAML + "user-auto-provisioning", "yes",
				SAML + "redirect-method", "GET",
				SAML + "assertion-consumer-service.url", "https://sp.example/store/auth/saml"));
		process(env);
		assertEquals("legacy", saml(env).defaultIdp());
		assertTrue(saml(env).idp().get("legacy").userAutoProvisioning());
		assertEquals(120, saml(env).idp().get("legacy").metadata().maxAge());
		var registration = registrations(env).getRegistration().get("legacy");
		assertEquals("old-sp", registration.getEntityId());
		assertEquals("https://sp.example/store/auth/saml?idp=legacy", registration.getAcs().getLocation());
		assertEquals(Saml2MessageBinding.REDIRECT, registration.getAssertingparty().getSinglesignon().getBinding());
		assertEquals(Boolean.FALSE, registration.getAssertingparty().getSinglesignon().getSignRequest());
	}

	@Test
	void perIdpKeysWinOverSingleIdpFallbacks() {
		var env = environment(Map.of(
				SAML + "default-idp", "acme",
				SAML + "idp-metadata.url", "classpath:old.xml",
				SAML + "idp.acme.metadata.url", "classpath:acme.xml",
				SAML + "user-auto-provisioning", "true",
				SAML + "idp.acme.user-auto-provisioning", "false"));
		process(env);
		assertEquals("acme", saml(env).defaultIdp());
		assertFalse(saml(env).idp().get("acme").userAutoProvisioning());
		assertEquals("classpath:acme.xml",
				registrations(env).getRegistration().get("acme").getAssertingparty().getMetadataUri());
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({
			"saml on, entrystore.auth.saml, on, entrystore.auth.saml.enabled, true",
			"cas yes, entrystore.auth.cas, YES, entrystore.auth.cas.enabled, true",
			"basic one, entrystore.auth.http-basic, 1, entrystore.auth.http-basic.enabled, true",
			"saml off, entrystore.auth.saml, off, entrystore.auth.saml.enabled, false",
			"cas no, entrystore.auth.cas, no, entrystore.auth.cas.enabled, false",
			"basic zero, entrystore.auth.http-basic, 0, entrystore.auth.http-basic.enabled, false"
	})
	void enableKeysUseExistingBooleanBinding(String name, String oldKey, String value, String key, boolean expected) {
		var env = environment(Map.of(oldKey, value));
		process(env);
		assertEquals(expected, Binder.get(env).bind(key, Boolean.class).get());
		assertTrue(warnings.stream().anyMatch(w -> w.contains(oldKey) && w.contains(key)));
	}

	@Test
	void casLoginUrlIsPreserved() {
		var env = environment(Map.of(
				"entrystore.auth.cas", "true",
				"entrystore.auth.cas.server.url", "https://cas.example",
				"entrystore.auth.cas.server.url.login", "https://cas.example/custom-login"));
		process(env);
		var cas = Binder.get(env).bind("entrystore.auth.cas", CasCustomConfiguration.class).get();
		assertTrue(cas.enabled());
		assertEquals("https://cas.example/custom-login", cas.server().resolvedLoginUrl());
	}

	@Test
	void modernKeysWinEvenFromALowerPrioritySource() {
		var env = environment(Map.of(
				"entrystore.auth.saml", "true",
				SAML + "idp.acme.metadata.url", "classpath:old.xml",
				SAML + "idp.acme.redirect-method", "invalid"));
		env.getPropertySources().addLast(new MapPropertySource("modern", Map.of(
				SAML + "enabled", "false",
				REG + "acme.assertingparty.metadata-uri", "classpath:new.xml",
				REG + "acme.assertingparty.singlesignon.binding", "post",
				REG + "acme.assertingparty.singlesignon.sign-request", "true")));
		process(env);
		assertFalse(saml(env).enabled());
		var registration = registrations(env).getRegistration().get("acme");
		assertEquals("classpath:new.xml", registration.getAssertingparty().getMetadataUri());
		assertEquals(Saml2MessageBinding.POST, registration.getAssertingparty().getSinglesignon().getBinding());
		assertEquals(Boolean.TRUE, registration.getAssertingparty().getSinglesignon().getSignRequest());
		assertTrue(warnings.stream().anyMatch(w -> w.contains("takes precedence")));
	}

	@Test
	void legacySigningDefaultDoesNotAffectModernRegistrations() {
		var env = environment(Map.of(
				SAML + "idp.legacy.metadata.url", "classpath:legacy.xml",
				REG + "modern.assertingparty.metadata-uri", "classpath:modern.xml"));
		process(env);
		var registrations = registrations(env).getRegistration();
		assertEquals(Boolean.FALSE,
				registrations.get("legacy").getAssertingparty().getSinglesignon().getSignRequest());
		assertNull(registrations.get("modern").getAssertingparty().getSinglesignon().getSignRequest());
	}

	@Test
	void relaxedEnvironmentVariableReplacementWins() {
		var env = environment(Map.of("entrystore.auth.http-basic", "true"));
		env.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment",
				Map.of("ENTRYSTORE_AUTH_HTTPBASIC_ENABLED", "false")));
		process(env);
		assertFalse(Binder.get(env).bind("entrystore.auth.http-basic.enabled", Boolean.class).get());
	}

	@Test
	void environmentVariablesDiscoverAndTranslateIdp() {
		var env = new MockEnvironment();
		env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
				"ENTRYSTORE_AUTH_SAML_IDP_ACME_METADATA_URL", "classpath:acme.xml",
				"ENTRYSTORE_AUTH_SAML_IDP_ACME_DOMAINS_1", "EXAMPLE.com")));
		process(env);
		assertEquals("classpath:acme.xml",
				registrations(env).getRegistration().get("acme").getAssertingparty().getMetadataUri());
		assertEquals(List.of("example.com"), saml(env).idp().get("acme").domains());
	}

	@Test
	void placeholdersAreResolvedBeforeTranslation() {
		var env = environment(Map.of(SAML + "idp.acme.metadata.url", "${metadata-location}",
				"metadata-location", "classpath:acme.xml"));
		process(env);
		assertEquals("classpath:acme.xml",
				registrations(env).getRegistration().get("acme").getAssertingparty().getMetadataUri());
	}

	record ListCase(String name, Map<String, Object> values, List<String> expected) {
		@Override
		public String toString() { return name; }
	}

	static Stream<ListCase> listForms() {
		return Stream.of(
				new ListCase("legacy 1-based", Map.of(".1", "one.example", ".2", "two.example"),
						List.of("one.example", "two.example")),
				new ListCase("comma-separated", Map.of("", "one.example,two.example"),
						List.of("one.example", "two.example")),
				new ListCase("native brackets", Map.of("[0]", "one.example", "[1]", "two.example"),
						List.of("one.example", "two.example")),
				new ListCase("zero-based dots", Map.of(".0", "one.example", ".1", "two.example"),
						List.of("one.example", "two.example")),
				new ListCase("legacy gap", Map.of(".1", "one.example", ".3", "ignored.example"), List.of("one.example")),
				new ListCase("bare overrides numbered", Map.of("", "one.example", ".1", "ignored.example"),
						List.of("one.example")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("listForms")
	void listFormsBindTheirEffectiveValues(ListCase example) {
		var env = new MockEnvironment();
		example.values().forEach((suffix, value) -> {
			env.setProperty(SAML + "redirect-domain-whitelist" + suffix, value.toString());
			env.setProperty(SAML + "idp.acme.domains" + suffix, value.toString());
		});
		process(env);
		assertEquals(example.expected(), saml(env).redirectDomainWhitelist());
		assertEquals(example.expected(), saml(env).idp().get("acme").domains());
	}

	@Test
	void missingFirstLegacyEntryRetainsTheLegacyDefaults() {
		var env = environment(Map.of(
				SAML + "redirect-domain-whitelist.2", "ignored.example",
				SAML + "idp.acme.domains.2", "ignored.example"));
		process(env);
		assertEquals(List.of(), saml(env).redirectDomainWhitelist());
		assertEquals(List.of("*"), saml(env).idp().get("acme").domains());
		assertTrue(warnings.stream().anyMatch(w -> w.contains("remain ignored")));
	}

	@Test
	void higherPriorityModernListReplacesLegacyList() {
		var env = environment(Map.of(SAML + "redirect-domain-whitelist.1", "old.example"));
		env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
				Map.of(SAML + "redirect-domain-whitelist[0]", "new.example")));
		process(env);
		assertEquals(List.of("new.example"), saml(env).redirectDomainWhitelist());
	}

	@Test
	void legacyBareValueStillWinsOverNumberedEntriesFromAnotherSource() {
		var env = environment(Map.of(SAML + "redirect-domain-whitelist.1", "ignored.example"));
		env.getPropertySources().addLast(new MapPropertySource("base",
				Map.of(SAML + "redirect-domain-whitelist", "bare.example")));
		process(env);
		assertEquals(List.of("bare.example"), saml(env).redirectDomainWhitelist());
	}

	@Test
	void invalidLegacyBindingNamesBothKeysWithoutPrintingTheValue() {
		var env = environment(Map.of(SAML + "idp.acme.redirect-method", "secret-invalid-value"));
		var error = assertThrows(IllegalStateException.class, () -> process(env));
		assertTrue(error.getMessage().contains(SAML + "idp.acme.redirect-method"));
		assertTrue(error.getMessage().contains(REG + "acme.assertingparty.singlesignon.binding"));
		assertFalse(error.getMessage().contains("secret-invalid-value"));
	}

	@Test
	void invalidLegacyAcsFailsBeforeBinding() {
		var env = environment(Map.of(SAML + "idps", "acme",
				SAML + "assertion-consumer-service.url", "not a URL"));
		assertThrows(IllegalStateException.class, () -> process(env));
	}

	@Test
	void currentConfigurationRequiresNoDeprecationWarning() {
		var env = environment(Map.of(SAML + "enabled", "true",
				REG + "acme.assertingparty.metadata-uri", "classpath:acme.xml",
				SAML + "idp.acme.domains", "example.com"));
		process(env);
		assertTrue(saml(env).enabled());
		assertTrue(warnings.isEmpty());
	}

	@Test
	void removedForwardedHeaderSettingStillWarns() {
		var env = environment(Map.of("entrystore.trust.x-forwarded-for", "false"));
		process(env);
		assertTrue(warnings.stream().anyMatch(w -> w.contains("was removed") && w.contains("forward-headers-strategy")));
	}

	@Test
	void runsAfterConfigDataAndCanBeLoadedThroughSpringFactories() {
		DeferredLogFactory logFactory = _ -> mock(Log.class);
		var processors = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
				.load(EnvironmentPostProcessor.class,
						SpringFactoriesLoader.ArgumentResolver.of(DeferredLogFactory.class, logFactory),
						(type, implementation, failure) -> {});
		var detector = processors.stream().filter(LegacyPropertyKeyDetector.class::isInstance).findFirst().orElseThrow();
		assertEquals(Ordered.LOWEST_PRECEDENCE, ((Ordered) detector).getOrder());
	}

	private MockEnvironment environment(Map<String, ?> properties) {
		var environment = new MockEnvironment();
		properties.forEach((key, value) -> environment.setProperty(key, value.toString()));
		return environment;
	}

	private void process(MockEnvironment environment) {
		Log log = mock(Log.class);
		doAnswer(invocation -> { warnings.add(invocation.getArgument(0).toString()); return null; })
				.when(log).warn(any());
		new LegacyPropertyKeyDetector(_ -> log).postProcessEnvironment(environment, null);
	}

	private SamlCustomConfiguration saml(MockEnvironment environment) {
		return Binder.get(environment).bind("entrystore.auth.saml", SamlCustomConfiguration.class).get();
	}

	private Saml2RelyingPartyProperties registrations(MockEnvironment environment) {
		return Binder.get(environment).bind("spring.security.saml2.relyingparty", Saml2RelyingPartyProperties.class).get();
	}
}
