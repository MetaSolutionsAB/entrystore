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
import org.springframework.core.Ordered;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPropertyTranslatorTest {

	private static final String ACS_URL = "https://store.example.org/store/auth/saml";
	private static final String METADATA_URL = "https://idp.example.org/metadata";
	private static final String SPRING_DEFAULT_ACS_LOCATION = "{baseUrl}/login/saml2/sso/{registrationId}";
	private static final String UNSPECIFIED_NAME_ID_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";
	private static final String EMAIL_NAME_ID_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";

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
		assertEquals(UNSPECIFIED_NAME_ID_FORMAT, registration.getNameIdFormat(), "5.x always asked for this format");
		assertTrue(saml(env).enabled());
	}

	@Test
	void multiIdp_acsUrlWithQuery_appendsTheIdpParameter() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL + "?tenant=a"));

		assertEquals(ACS_URL + "?tenant=a&idp=google", registration(env, "google").getAcs().getLocation());
	}

	// 5.x sent the request with GET unless redirect-method was post, so an unknown value means redirect.
	@ParameterizedTest(name = "redirect-method={0} -> binding {1}")
	@CsvSource({"get, REDIRECT", "GET, REDIRECT", "post, POST", "Post, POST", "artifact, REDIRECT"})
	void multiIdp_redirectMethod_becomesTheSingleSignOnBinding(String redirectMethod, Saml2MessageBinding binding) {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idp.google.redirect-method", redirectMethod));

		assertEquals(binding, registration(env, "google").getAssertingparty().getSinglesignon().getBinding());
	}

	@Test
	void multiIdp_withoutRedirectMethod_defaultsToTheRedirectBinding() {
		var env = translate(multiIdpConfig("google"));

		assertEquals(Saml2MessageBinding.REDIRECT,
				registration(env, "google").getAssertingparty().getSinglesignon().getBinding(),
				"5.x defaulted redirect-method to get");
	}

	@Test
	void multiIdp_explicitNameIdFormat_skipsTheTranslation() {
		var env = translate(multiIdpConfig("google")
				.withProperty("spring.security.saml2.relyingparty.registration.google.assertingparty.metadata-uri",
						METADATA_URL)
				.withProperty("spring.security.saml2.relyingparty.registration.google.name-id-format",
						EMAIL_NAME_ID_FORMAT));

		assertEquals(EMAIL_NAME_ID_FORMAT, registration(env, "google").getNameIdFormat());
		assertWarned("settings for IdP 'google' are ignored");
	}

	@Test
	void multiIdp_idpWithoutMetadataUrl_isNotTranslated() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml", "new")
				.withProperty("entrystore.auth.saml.idps.1", "google")
				.withProperty("entrystore.auth.saml.idp.google.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url", ACS_URL));

		assertNull(registration(env, "google"));
		assertWarned("IdP 'google' is not translated");
	}

	@Test
	void multiIdp_idWithSixXRegistrationKeysInAnySpelling_isNotTranslated() {
		var env = withEnvironmentVariables(multiIdpConfig("google"),
				Map.of("SPRING_SECURITY_SAML2_RELYINGPARTY_REGISTRATION_GOOGLE_ENTITYID", "explicit"));

		translate(env);

		var registration = registration(env, "google");
		assertEquals("explicit", registration.getEntityId());
		assertEquals(SPRING_DEFAULT_ACS_LOCATION, registration.getAcs().getLocation(),
				"a partial 6.x migration must not be overridden");
		assertWarned("settings for IdP 'google' are ignored");
	}

	@Test
	void multiIdp_bareIdpsValue_winsOverNumberedEntries() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idps", "google")
				.withProperty("entrystore.auth.saml.idps.1", "other"));

		assertEquals(ACS_URL + "?idp=google", registration(env, "google").getAcs().getLocation());
	}

	@Test
	void multiIdp_idpsListWithAGap_translatesTheIdpsBeforeTheGap() {
		var env = translate(multiIdpConfig("google")
				.withProperty("entrystore.auth.saml.idps.3", "okta")
				.withProperty("entrystore.auth.saml.idp.okta.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp.okta.metadata.url", METADATA_URL));

		assertEquals(ACS_URL + "?idp=google", registration(env, "google").getAcs().getLocation());
		assertNull(registration(env, "okta"));
	}

	@Test
	void multiIdp_withoutIdpsList_findsTheIdpsByTheirMetadataUrl() {
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
	}

	// --- 5.x single-IdP form ---

	@Test
	void singleIdp_isTranslatedToARegistrationNamedDefault() {
		var env = translate(singleIdpConfig());

		var registration = registration(env, "default");
		assertEquals("urn:example:sp", registration.getEntityId());
		assertEquals(METADATA_URL, registration.getAssertingparty().getMetadataUri());
		assertEquals(ACS_URL, registration.getAcs().getLocation(), "5.x used the single-IdP ACS URL verbatim");
		assertEquals(Saml2MessageBinding.REDIRECT, registration.getAssertingparty().getSinglesignon().getBinding(),
				"5.x defaulted redirect-method to get");
		assertEquals(UNSPECIFIED_NAME_ID_FORMAT, registration.getNameIdFormat());
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
	void singleIdp_multiIdpKeys_areIgnoredAsInFiveX() {
		var env = translate(singleIdpConfig()
				.withProperty("entrystore.auth.saml.idps.1", "google")
				.withProperty("entrystore.auth.saml.idp.google.metadata.url", METADATA_URL));

		assertNull(registration(env, "google"));
	}

	@Test
	void bothSamlFormsWithoutSelector_translateTheSingleIdpForm() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.enabled", "true")
				.withProperty("entrystore.auth.saml.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp-metadata.url", METADATA_URL)
				.withProperty("entrystore.auth.saml.idps.1", "google")
				.withProperty("entrystore.auth.saml.idp.google.metadata.url", METADATA_URL));

		assertEquals(METADATA_URL, registration(env, "default").getAssertingparty().getMetadataUri());
		assertNull(registration(env, "google"));
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
	}

	@Test
	void whitelistNumberedFromZero_isLeftToSpring() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.0", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "portal.example.org"));

		assertEquals(List.of("app.example.org", "portal.example.org"), saml(env).redirectDomainWhitelist());
		assertTrue(warnings.isEmpty(), String.valueOf(warnings));
	}

	@Test
	void bracketedWhitelist_isLeftToSpring() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist[0]", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "portal.example.org"));

		// Spring binds .1 as the second entry of the list that [0] starts.
		assertEquals(List.of("app.example.org", "portal.example.org"), saml(env).redirectDomainWhitelist());
		assertTrue(warnings.isEmpty(), String.valueOf(warnings));
	}

	@Test
	void bareWhitelistWithNumberedEntries_keepsTheBareValue() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist", "app.example.org")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "portal.example.org"));

		assertEquals(List.of("app.example.org"), saml(env).redirectDomainWhitelist());
		assertWarned("'entrystore.auth.saml.redirect-domain-whitelist.1'",
				"ignored because 'entrystore.auth.saml.redirect-domain-whitelist' is set");
	}

	@Test
	void emptyBareWhitelistWithNumberedEntries_keepsTheWhitelistEmpty() {
		var env = translate(new MockEnvironment()
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist", "")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist.1", "portal.example.org"));

		assertEquals(List.of(), saml(env).redirectDomainWhitelist());
		assertWarned("ignored because 'entrystore.auth.saml.redirect-domain-whitelist' is set");
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
	void runsAfterConfigData() {
		assertEquals(Ordered.LOWEST_PRECEDENCE - 2, new LegacyPropertyTranslator(_ -> new RecordingLog(warnings)).getOrder());
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
