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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Bindable.BindRestriction;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPropertyTranslatorTest {

	private static final String ACS_URL = "https://store.example.org/store/auth/saml";
	private static final String METADATA_URL = "https://idp.example.org/metadata";
	private static final String SPRING_DEFAULT_ACS_LOCATION = "{baseUrl}/login/saml2/sso/{registrationId}";

	private final List<String> warnings = new ArrayList<>();

	// --- enable keys ---

	@ParameterizedTest(name = "{0}={1} -> {0}.enabled={2}")
	@CsvSource({
			"entrystore.auth.saml, on, true",
			"entrystore.auth.saml, NEW, true",
			"entrystore.auth.saml, true, false",
			"entrystore.auth.saml, off, false",
			"entrystore.auth.cas, on, true",
			"entrystore.auth.cas, true, false",
			"entrystore.auth.http-basic, on, true",
			"entrystore.auth.http-basic, TRUE, true",
			"entrystore.auth.http-basic, yes, false"
	})
	void legacyEnableKey_isTranslatedWithTheValueFiveXDerived(String legacyKey, String value, String enabled) {
		var env = translate(new MockEnvironment().withProperty(legacyKey, value));

		assertEquals(enabled, env.getProperty(legacyKey + ".enabled"));
		assertWarned("'" + legacyKey + "'", "'" + legacyKey + ".enabled=" + enabled + "'");
	}

	@Test
	void explicitEnabledKey_winsInAnySpelling() {
		var env = withEnvironmentVariables(new MockEnvironment().withProperty("entrystore.auth.cas", "on"),
				Map.of("ENTRYSTORE_AUTH_CAS_ENABLED", "false"));

		translate(env);

		assertEquals("false", env.getProperty("entrystore.auth.cas.enabled"));
		assertWarned("ignored because 'entrystore.auth.cas.enabled' is set");
	}

	// --- 5.x multi-IdP form ---

	@Test
	void multiIdp_isTranslatedToTheRegistrationFiveXBuilt() {
		var env = translate(multiIdpConfig("google").withProperty("entrystore.auth.saml.idp.google.redirect-method", "post"));

		var registration = registration(env, "google");
		assertEquals("urn:example:sp", registration.getEntityId());
		assertEquals(METADATA_URL, registration.getAssertingparty().getMetadataUri());
		assertEquals(ACS_URL + "?idp=google", registration.getAcs().getLocation());
		assertEquals(Saml2MessageBinding.POST, registration.getAssertingparty().getSinglesignon().getBinding());
		// 5.x never signed authentication requests.
		assertEquals(Boolean.FALSE, registration.getAssertingparty().getSinglesignon().getSignRequest());
		assertTrue(saml(env).enabled());
	}

	@Test
	void multiIdp_acsUrlWithQuery_appendsTheIdpParameter() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL + "?tenant=a"));

		assertEquals(ACS_URL + "?tenant=a&idp=google", registration(env, "google").getAcs().getLocation());
	}

	@ParameterizedTest(name = "redirect-method={0} -> binding {1}")
	@CsvSource({"get, REDIRECT", "GET, REDIRECT", "post, POST", "Post, POST"})
	void multiIdp_redirectMethod_becomesTheSingleSignOnBinding(String redirectMethod, Saml2MessageBinding binding) {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idp.google.redirect-method", redirectMethod));

		assertEquals(binding, registration(env, "google").getAssertingparty().getSinglesignon().getBinding());
	}

	@Test
	void multiIdp_unknownRedirectMethod_leavesTheBindingToTheMetadata() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idp.google.redirect-method", "artifact"));

		assertNull(registration(env, "google").getAssertingparty().getSinglesignon().getBinding());
		assertWarned("redirect-method=artifact");
	}

	@Test
	void multiIdp_incompleteIdp_isSkippedAsFiveXDid() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml", "new")
				.withProperty("entrystore.auth.saml.idps.1", "google")
				.withProperty("entrystore.auth.saml.idp.google.metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL));

		assertNull(registration(env, "google"));
		assertWarned("IdP 'google' is not translated");
	}

	@Test
	void multiIdp_idWithSixXRegistrationKeys_isNotTranslated() {
		var env = translate(multiIdpConfig("google")
				.withProperty("spring.security.saml2.relyingparty.registration.google.entity-id", "explicit"));

		var registration = registration(env, "google");
		assertEquals("explicit", registration.getEntityId());
		assertEquals(SPRING_DEFAULT_ACS_LOCATION, registration.getAcs().getLocation(),
				"a partial 6.x migration must not be overridden");
		assertWarned("settings for IdP 'google' are ignored");
	}

	@Test
	void multiIdp_sixXRegistrationInAnEnvironmentVariable_isRespected() {
		var env = withEnvironmentVariables(multiIdpConfig("google"),
				Map.of("SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_GOOGLE_ENTITYID", "explicit"));

		translate(env);

		var registration = registration(env, "google");
		assertEquals("explicit", registration.getEntityId());
		assertEquals(SPRING_DEFAULT_ACS_LOCATION, registration.getAcs().getLocation());
	}

	@Test
	void multiIdp_bareIdpsValue_namesTheIdp() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idps", "google")
				.withProperty("entrystore.auth.saml.idps.1", "other"));

		assertEquals(ACS_URL + "?idp=google", registration(env, "google").getAcs().getLocation());
		assertWarned("'entrystore.auth.saml.idps' entries [.1]");
	}

	@Test
	void multiIdp_idpsListWithAGap_translatesTheIdpsBeforeTheGap() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idps.3", "okta")
				.withProperty("entrystore.auth.saml.idp.okta.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp.okta.metadata.url", METADATA_URL));

		assertEquals(ACS_URL + "?idp=google", registration(env, "google").getAcs().getLocation());
		assertNull(registration(env, "okta"));
		assertWarned("'entrystore.auth.saml.idps' entries [.3]");
	}

	@Test
	void multiIdp_withoutIdpsList_findsTheIdpsByTheirFiveXKeys() {
		// A 6.0 configuration that followed the old detector's rename hint: no idps list and no selector.
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.enabled", "true")
				.withProperty("entrystore.auth.saml.idp.google.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp.google.metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL));

		assertEquals(ACS_URL + "?idp=google", registration(env, "google").getAcs().getLocation());
	}

	@Test
	void multiIdp_whileSamlIsDisabled_emitsNoRegistration() {
		var env = translate(multiIdpConfig("google").withProperty("entrystore.auth.saml", "off"));

		assertNull(registration(env, "google"));
		assertWarned("SAML is disabled");
	}

	@Test
	void multiIdp_idUnusableAsRegistrationId_abortsStartup() {
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml", "new")
				.withProperty("entrystore.auth.saml.idps.1", "my idp")
				.withProperty("entrystore.auth.saml.idp.my idp.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp.my idp.metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL);

		String message = assertAborts(env);

		assertTrue(message.contains("'my idp'"), message);
	}

	@Test
	void multiIdp_acsUrlNotEndingInTheFiveXPath_warns() {
		translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", "https://store.example.org/sso"));

		assertWarned("does not end in /auth/saml");
	}

	// --- 5.x single-IdP form ---

	@Test
	void singleIdp_isTranslatedToARegistrationNamedDefault() {
		var env = translate(singleIdpConfig());

		var registration = registration(env, "default");
		assertEquals("urn:example:sp", registration.getEntityId());
		assertEquals(METADATA_URL, registration.getAssertingparty().getMetadataUri());
		assertEquals(ACS_URL, registration.getAcs().getLocation(), "5.x used the single-IdP ACS URL verbatim");
		var saml = saml(env);
		assertEquals("default", saml.defaultIdp());
		assertEquals(List.of("*"), saml.idp().get("default").domains(), "every login routes to the one IdP");
	}

	@Test
	void singleIdp_defaultIdp_namesTheRegistration() {
		var env = translate(singleIdpConfig().withProperty("entrystore.auth.saml.default-idp", "google"));

		assertEquals(ACS_URL, registration(env, "google").getAcs().getLocation());
		assertNull(registration(env, "default"));
	}

	@ParameterizedTest(name = "user-auto-provisioning={0} -> {1}")
	@CsvSource({"on, true", "ON, true", "true, false", "yes, false"})
	void singleIdp_userAutoProvisioning_isEnabledOnlyByOn(String value, boolean enabled) {
		var env = translate(singleIdpConfig().withProperty("entrystore.auth.saml.user-auto-provisioning", value));

		assertEquals(enabled, saml(env).idp().get("default").userAutoProvisioning());
	}

	@Test
	void singleIdp_metadataMaxAge_movesToTheIdp() {
		var env = translate(singleIdpConfig().withProperty("entrystore.auth.saml.idp-metadata.max-age", "86400"));

		assertEquals(86400L, saml(env).idp().get("default").metadata().maxAge());
	}

	@Test
	void singleIdp_metadataMaxAgeBelowTheSixXMinimum_abortsStartup() {
		String message = assertAborts(singleIdpConfig().withProperty("entrystore.auth.saml.idp-metadata.max-age", "30"));

		assertTrue(message.contains("'entrystore.auth.saml.idp-metadata.max-age=30'"), message);
	}

	@Test
	void singleIdp_multiIdpKeys_areIgnoredAsInFiveX() {
		var env = translate(singleIdpConfig()
				.withProperty("entrystore.auth.saml.idps.1", "google")
				.withProperty("entrystore.auth.saml.idp.google.metadata.url", METADATA_URL));

		assertNull(registration(env, "google"));
		assertWarned("multi-IdP SAML settings");
	}

	@Test
	void bothSamlFormsWithoutSelector_abortStartup() {
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.enabled", "true")
				.withProperty("entrystore.auth.saml.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idps.1", "google");

		String message = assertAborts(env);

		assertTrue(message.contains("Both the 5.x single-IdP SAML settings"), message);
	}

	// --- CAS ---

	@Test
	void casLoginUrl_isTranslated() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.cas.server.url", "https://cas.example.org/cas")
				.withProperty("entrystore.auth.cas.server.url.login", "https://cas.example.org/custom-login"));

		assertEquals("https://cas.example.org/custom-login", cas(env).server().resolvedLoginUrl());
	}

	@Test
	void casLoginUrl_explicitSixXKeyWins() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.cas.server.url", "https://cas.example.org/cas")
				.withProperty("entrystore.auth.cas.server.url.login", "https://cas.example.org/legacy-login")
				.withProperty("entrystore.auth.cas.server.url-login", "https://cas.example.org/login-6x"));

		assertEquals("https://cas.example.org/login-6x", cas(env).server().resolvedLoginUrl());
	}

	// --- 5.x numbered lists ---

	@Test
	void numberedWhitelist_bindsTheEntriesFiveXRead() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.2", "portal.example.org"));

		assertEquals(List.of("app.example.org", "portal.example.org"), saml(env).redirectDomainWhitelist());
		assertWarned("redirect-domain-whitelist=app.example.org,portal.example.org");
	}

	@Test
	void numberedWhitelistWithAGap_bindsTheEntriesBeforeTheGap() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.3", "portal.example.org"));

		assertEquals(List.of("app.example.org"), saml(env).redirectDomainWhitelist());
		assertWarned("'entrystore.auth.saml.redirect-domain-whitelist' entries [.3]");
	}

	@Test
	void numberedWhitelistFromZeroAndFromOne_abortsStartup() {
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.0", "evil.example.net")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "app.example.org");

		String message = assertAborts(env);

		assertTrue(message.contains("'entrystore.auth.saml.redirect-domain-whitelist' has entries numbered from .0 and from .1"),
				message);
	}

	@Test
	void whitelistNumberedFromZeroOnly_isLeftToSpring() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.0", "app.example.org"));

		assertEquals(List.of("app.example.org"), saml(env).redirectDomainWhitelist());
		assertTrue(warnings.isEmpty(), String.valueOf(warnings));
	}

	@Test
	void bareWhitelistWithNumberedEntries_keepsTheBareValue() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "portal.example.org"));

		assertEquals(List.of("app.example.org"), saml(env).redirectDomainWhitelist());
		assertWarned("because the bare value is set");
	}

	@Test
	void whitelistInEnvironmentVariablesNumberedFromOne_abortsStartup() {
		var env = withEnvironmentVariables(new MockEnvironment(),
				Map.of("ENTRYSTORE_AUTH_SAML_REDIRECTDOMAINWHITELIST_1", "app.example.org"));

		String message = assertAborts(env);

		assertTrue(message.contains("environment variables numbered from _1"), message);
	}

	@Test
	void numberedIdpDomains_bindTheEntriesFiveXRead() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.idp.google.domains.1", "Example.org")
				.withProperty("entrystore.auth.saml.idp.google.domains.2", "example.com"));

		assertEquals(List.of("example.org", "example.com"), saml(env).idp().get("google").domains());
	}

	// --- no legacy keys, wiring ---

	@Test
	void noLegacyKeys_addNoPropertySourceAndLogNothing() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.enabled", "true")
				.withProperty("entrystore.auth.saml.default-idp", "keycloak")
				.withProperty("entrystore.auth.saml.idp.keycloak.user-auto-provisioning", "on")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist", "localhost"));

		assertFalse(env.getPropertySources().contains(LegacyPropertyTranslator.PROPERTY_SOURCE_NAME));
		assertTrue(warnings.isEmpty(), String.valueOf(warnings));
	}

	@Test
	void runsAfterTheIndexedListValidator() {
		var translator = new LegacyPropertyTranslator(_ -> new RecordingLog(warnings));

		assertTrue(translator.getOrder() > new IndexedListConfigValidator().getOrder());
	}

	@Test
	void springFactoriesWiresThisTranslator() {
		assertTrue(RegisteredEnvironmentPostProcessors.load().stream().anyMatch(LegacyPropertyTranslator.class::isInstance));
	}

	private MockEnvironment translate(MockEnvironment env) {
		new LegacyPropertyTranslator(_ -> new RecordingLog(warnings)).postProcessEnvironment(env, null);
		return env;
	}

	/** Asserts that one WARN contains every fragment. */
	private void assertWarned(String... fragments) {
		assertTrue(warnings.stream().anyMatch(warning -> Arrays.stream(fragments).allMatch(warning::contains)),
				String.valueOf(warnings));
	}

	private static MockEnvironment withEnvironmentVariables(MockEnvironment env, Map<String, Object> variables) {
		env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
		return env;
	}

	private String assertAborts(MockEnvironment env) {
		var aborted = assertThrows(IllegalStateException.class, () -> translate(env));
		assertTrue(aborted.getMessage().startsWith("EntryStore startup aborted"), aborted.getMessage());
		return aborted.getMessage();
	}

	/** The dev.entryscape.com shape: a complete 5.x multi-IdP configuration for one IdP. */
	private static MockEnvironment multiIdpConfig(String id) {
		return new MockEnvironment()
				.withProperty("entrystore.auth.saml", "new")
				.withProperty("entrystore.auth.saml.idps.1", id)
				.withProperty("entrystore.auth.saml.idp." + id + ".relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp." + id + ".metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL);
	}

	private static MockEnvironment singleIdpConfig() {
		return new MockEnvironment()
				.withProperty("entrystore.auth.saml", "on")
				.withProperty("entrystore.auth.saml.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp-metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL);
	}

	// Bound as @ConfigurationProperties binds them: a scalar legacy enable key at the prefix is not a direct value.
	private static SamlCustomConfiguration saml(ConfigurableEnvironment env) {
		return Binder.get(env).bindOrCreate("entrystore.auth.saml",
				Bindable.of(SamlCustomConfiguration.class).withBindRestrictions(BindRestriction.NO_DIRECT_PROPERTY));
	}

	private static CasCustomConfiguration cas(ConfigurableEnvironment env) {
		return Binder.get(env).bindOrCreate("entrystore.auth.cas",
				Bindable.of(CasCustomConfiguration.class).withBindRestrictions(BindRestriction.NO_DIRECT_PROPERTY));
	}

	private static Saml2RelyingPartyProperties.Registration registration(ConfigurableEnvironment env, String id) {
		return Binder.get(env).bindOrCreate("spring.security.saml2.relyingparty", Saml2RelyingPartyProperties.class)
				.getRegistration().get(id);
	}
}
