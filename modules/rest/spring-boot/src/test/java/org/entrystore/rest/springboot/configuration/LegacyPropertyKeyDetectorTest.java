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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessorsFactory;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPropertyKeyDetectorTest {

	@Test
	void noLegacyKeysPresent_doesNothing() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment();

		detector.postProcessEnvironment(env, null);

		assertTrue(warnings.isEmpty());
	}

	@Test
	void legacyEnableKeyTruthy_failsFastWithBothKeysInMessage() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment().withProperty("entrystore.auth.saml", "true");

		var ex = assertThrows(IllegalStateException.class,
				() -> detector.postProcessEnvironment(env, null));

		assertTrue(ex.getMessage().contains("'entrystore.auth.saml'"));
		assertTrue(ex.getMessage().contains("'entrystore.auth.saml.enabled'"));
		assertFalse(ex.getMessage().contains("Additionally"),
				"Fail-fast message must not advertise a falsy section when there are no falsy hits");
		assertTrue(warnings.isEmpty(),
				"Truthy fail-fast should surface findings via the exception, not via the deferred log");
	}

	@Test
	void legacyEnableKeyFalsy_warnsOnceNamingBothKeys() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment().withProperty("entrystore.auth.saml", "false");

		detector.postProcessEnvironment(env, null);

		assertEquals(1, warnings.size());
		assertTrue(warnings.getFirst().contains("'entrystore.auth.saml'"));
		assertTrue(warnings.getFirst().contains("'entrystore.auth.saml.enabled'"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"true", "TRUE", "True", "on", "On", "yes", "YES", "enabled", "1",
			"  true  ", " enabled "})
	void legacyEnableKeyTruthyVariants_allFailFast(String truthyValue) {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment().withProperty("entrystore.auth.cas", truthyValue);

		var ex = assertThrows(IllegalStateException.class,
				() -> detector.postProcessEnvironment(env, null));

		assertTrue(ex.getMessage().contains("'entrystore.auth.cas'"),
				"Truthy fail-fast must name the legacy key for value: " + truthyValue);
		assertTrue(ex.getMessage().contains("'entrystore.auth.cas.enabled'"),
				"Truthy fail-fast must name the replacement key for value: " + truthyValue);
		assertTrue(warnings.isEmpty(),
				"Truthy fail-fast should surface findings via the exception, not via the deferred log");
	}

	@ParameterizedTest
	@ValueSource(strings = {"false", "FALSE", "off", "Off", "no", "disabled", "0", "", "   ",
			"maybe", "2"})
	void legacyEnableKeyFalsyVariants_allWarnOnly(String falsyValue) {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment().withProperty("entrystore.auth.cas", falsyValue);

		detector.postProcessEnvironment(env, null);

		assertEquals(1, warnings.size());
		assertTrue(warnings.getFirst().contains("'entrystore.auth.cas'"));
		assertTrue(warnings.getFirst().contains("'entrystore.auth.cas.enabled'"));
	}

	@Test
	void legacySamlIdpKey_truthy_failsFastNamingBothKeys() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.user-auto-provisioning", "enabled");

		var ex = assertThrows(IllegalStateException.class,
				() -> detector.postProcessEnvironment(env, null));

		assertTrue(ex.getMessage().contains("'entrystore.auth.saml.user-auto-provisioning'"));
		assertTrue(ex.getMessage()
				.contains("'entrystore.auth.saml.idp.<idp-id>.user-auto-provisioning'"));
		assertTrue(warnings.isEmpty(),
				"Truthy fail-fast on a SAML IdP-prefix key should surface via the exception, not the log");
	}

	@Test
	void legacySamlIdpKeys_warnIndividually() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.relying-party-id", "urn:example:sp")
				.withProperty("entrystore.auth.saml.idp-metadata.url", "https://idp.example/metadata")
				.withProperty("entrystore.auth.saml.idp-metadata.max-age", "PT1H")
				.withProperty("entrystore.auth.saml.user-auto-provisioning", "off")
				.withProperty("entrystore.auth.saml.redirect-method", "post");

		detector.postProcessEnvironment(env, null);

		assertEquals(5, warnings.size());
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("entrystore.auth.saml.relying-party-id")
						&& w.contains("entrystore.auth.saml.idp.<idp-id>.relying-party-id")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("entrystore.auth.saml.idp-metadata.url")
						&& w.contains("entrystore.auth.saml.idp.<idp-id>.metadata.url")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("entrystore.auth.saml.idp-metadata.max-age")
						&& w.contains("entrystore.auth.saml.idp.<idp-id>.metadata.max-age")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("entrystore.auth.saml.user-auto-provisioning")
						&& w.contains("entrystore.auth.saml.idp.<idp-id>.user-auto-provisioning")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("entrystore.auth.saml.redirect-method")
						&& w.contains("entrystore.auth.saml.idp.<idp-id>.redirect-method")));
	}

	@Test
	void excludedSamlDuplicateKeys_doNotFire() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.assertion-consumer-service.url",
						"https://sp.example/saml/sso")
				.withProperty("entrystore.auth.saml.redirect-success.url", "/home")
				.withProperty("entrystore.auth.saml.redirect-failure.url", "/login");

		detector.postProcessEnvironment(env, null);

		assertTrue(warnings.isEmpty(),
				"Keys whose string value matches a live new key must not be flagged");
	}

	@Test
	void liveSamlIdpKeys_doNotFire() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml.default-idp", "keycloak")
				.withProperty("entrystore.auth.saml.idps.1", "keycloak")
				.withProperty("entrystore.auth.saml.redirect-domain-whitelist", "localhost")
				.withProperty("entrystore.auth.saml.idp.keycloak.user-auto-provisioning", "on");

		detector.postProcessEnvironment(env, null);

		assertTrue(warnings.isEmpty(),
				"Live SAML config keys that are not legacy must not trigger any diagnostic");
	}

	@Test
	void multipleFalsyLegacyKeys_aggregateAllOffencesAsWarnings() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.http-basic", "false")
				.withProperty("entrystore.auth.saml", "off")
				.withProperty("entrystore.auth.cas", "0");

		detector.postProcessEnvironment(env, null);

		assertEquals(3, warnings.size());
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("'entrystore.auth.http-basic'")
						&& w.contains("'entrystore.auth.http-basic.enabled'")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("'entrystore.auth.saml'")
						&& w.contains("'entrystore.auth.saml.enabled'")));
		assertTrue(warnings.stream().anyMatch(w ->
				w.contains("'entrystore.auth.cas'")
						&& w.contains("'entrystore.auth.cas.enabled'")));
	}

	@Test
	void mixedTruthyAndFalsy_failsFastWithBothKeysInMessage() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml", "true")
				.withProperty("entrystore.auth.cas", "false");

		var ex = assertThrows(IllegalStateException.class,
				() -> detector.postProcessEnvironment(env, null));

		assertTrue(ex.getMessage().contains("'entrystore.auth.saml'"));
		assertTrue(ex.getMessage().contains("'entrystore.auth.saml.enabled'"));
		assertTrue(ex.getMessage().contains("'entrystore.auth.cas'"),
				"Falsy hits should still surface in the fail-fast message so operators see both");
		assertTrue(ex.getMessage().contains("'entrystore.auth.cas.enabled'"));
		assertTrue(ex.getMessage().contains("Additionally"),
				"Falsy hits must appear under their own header so operators can tell them apart from the abort cause");
		assertTrue(warnings.isEmpty(),
				"Mixed truthy/falsy must surface all findings via the exception, not the deferred log");
	}

	@Test
	void legacyAndNewKeyBothTruthy_stillFailsFast() {
		var warnings = new ArrayList<String>();
		var detector = newDetector(warnings);
		var env = new MockEnvironment()
				.withProperty("entrystore.auth.saml", "true")
				.withProperty("entrystore.auth.saml.enabled", "true");

		var ex = assertThrows(IllegalStateException.class,
				() -> detector.postProcessEnvironment(env, null));

		assertTrue(ex.getMessage().contains("'entrystore.auth.saml'"),
				"Detector still fail-fasts on the legacy key even when the new key is also set");
		assertTrue(warnings.isEmpty(),
				"Setting the new key alongside the legacy one must not produce a WARN; the diagnostic comes via the exception");
	}

	@Test
	void isTruthy_recognisesAllVariantsAndRejectsOthers() {
		assertTrue(LegacyPropertyKeyDetector.isTruthy("true"));
		assertTrue(LegacyPropertyKeyDetector.isTruthy("TRUE"));
		assertTrue(LegacyPropertyKeyDetector.isTruthy(" on "));
		assertTrue(LegacyPropertyKeyDetector.isTruthy("yes"));
		assertTrue(LegacyPropertyKeyDetector.isTruthy("enabled"));
		assertTrue(LegacyPropertyKeyDetector.isTruthy("1"));

		assertFalse(LegacyPropertyKeyDetector.isTruthy(null));
		assertFalse(LegacyPropertyKeyDetector.isTruthy(""));
		assertFalse(LegacyPropertyKeyDetector.isTruthy("false"));
		assertFalse(LegacyPropertyKeyDetector.isTruthy("0"));
		assertFalse(LegacyPropertyKeyDetector.isTruthy("disabled"));
		assertFalse(LegacyPropertyKeyDetector.isTruthy("maybe"));
	}

	@Test
	void legacyKeyMap_containsExpectedEightEntries() {
		var map = LegacyPropertyKeyDetector.legacyKeys();

		assertEquals(8, map.size());
		assertEquals("entrystore.auth.http-basic.enabled", map.get("entrystore.auth.http-basic"));
		assertEquals("entrystore.auth.saml.enabled", map.get("entrystore.auth.saml"));
		assertEquals("entrystore.auth.cas.enabled", map.get("entrystore.auth.cas"));
		assertEquals("entrystore.auth.saml.idp.<idp-id>.relying-party-id",
				map.get("entrystore.auth.saml.relying-party-id"));
		assertEquals("entrystore.auth.saml.idp.<idp-id>.metadata.url",
				map.get("entrystore.auth.saml.idp-metadata.url"));
		assertEquals("entrystore.auth.saml.idp.<idp-id>.metadata.max-age",
				map.get("entrystore.auth.saml.idp-metadata.max-age"));
		assertEquals("entrystore.auth.saml.idp.<idp-id>.user-auto-provisioning",
				map.get("entrystore.auth.saml.user-auto-provisioning"));
		assertEquals("entrystore.auth.saml.idp.<idp-id>.redirect-method",
				map.get("entrystore.auth.saml.redirect-method"));
		assertFalse(map.containsKey("entrystore.auth.saml.assertion-consumer-service.url"));
		assertFalse(map.containsKey("entrystore.auth.saml.redirect-success.url"));
		assertFalse(map.containsKey("entrystore.auth.saml.redirect-failure.url"));
	}

	@Test
	void legacyKeysMap_isImmutable() {
		assertThrows(UnsupportedOperationException.class,
				() -> LegacyPropertyKeyDetector.legacyKeys().put("x", "y"),
				"LEGACY_KEYS is shared global state; legacyKeys() must expose an immutable view");
	}

	@Test
	void runsAfterConfigData_viaLowestPrecedenceOrdering() {
		assertEquals(Ordered.LOWEST_PRECEDENCE, newDetector(new ArrayList<>()).getOrder(),
				"Must keep LOWEST_PRECEDENCE so the detector runs after ConfigDataEnvironmentPostProcessor "
						+ "and sees entrystore.properties imported via spring.config.import");
	}

	@Test
	void springFactoriesWiresThisDetector() {
		var warnings = new ArrayList<String>();
		DeferredLogFactory deferredLogFactory = _ -> new RecordingLog(warnings);

		var factory = EnvironmentPostProcessorsFactory.fromSpringFactories(getClass().getClassLoader());
		List<EnvironmentPostProcessor> processors =
				factory.getEnvironmentPostProcessors(deferredLogFactory, new DefaultBootstrapContext());

		assertTrue(processors.stream().anyMatch(LegacyPropertyKeyDetector.class::isInstance),
				"META-INF/spring.factories must register LegacyPropertyKeyDetector as an EnvironmentPostProcessor");
	}

	private LegacyPropertyKeyDetector newDetector(List<String> warningSink) {
		DeferredLogFactory factory = _ -> new RecordingLog(warningSink);
		return new LegacyPropertyKeyDetector(factory);
	}

	/**
	 * Minimal {@link Log} that records single-argument {@code warn} calls. The detector only ever
	 * emits via single-argument WARN; every other level and the two-argument {@code warn} overload
	 * fail loudly with an {@link AssertionError} so an unexpected production log call surfaces as a
	 * test failure instead of being silently dropped.
	 */
	private static final class RecordingLog implements Log {

		private final List<String> warnings;

		RecordingLog(List<String> warnings) {
			this.warnings = warnings;
		}

		@Override public void warn(Object message) {
			warnings.add(String.valueOf(message));
		}

		@Override public boolean isWarnEnabled() { return true; }
		@Override public boolean isDebugEnabled() { return false; }
		@Override public boolean isErrorEnabled() { return false; }
		@Override public boolean isFatalEnabled() { return false; }
		@Override public boolean isInfoEnabled() { return false; }
		@Override public boolean isTraceEnabled() { return false; }

		@Override public void debug(Object message) { throw unexpected("debug"); }
		@Override public void debug(Object message, Throwable t) { throw unexpected("debug"); }
		@Override public void error(Object message) { throw unexpected("error"); }
		@Override public void error(Object message, Throwable t) { throw unexpected("error"); }
		@Override public void fatal(Object message) { throw unexpected("fatal"); }
		@Override public void fatal(Object message, Throwable t) { throw unexpected("fatal"); }
		@Override public void info(Object message) { throw unexpected("info"); }
		@Override public void info(Object message, Throwable t) { throw unexpected("info"); }
		@Override public void trace(Object message) { throw unexpected("trace"); }
		@Override public void trace(Object message, Throwable t) { throw unexpected("trace"); }
		@Override public void warn(Object message, Throwable t) { throw unexpected("warn(message, throwable)"); }

		private static AssertionError unexpected(String level) {
			return new AssertionError("Unexpected log call at level '" + level
					+ "'; the detector should only emit via single-argument WARN");
		}
	}
}
