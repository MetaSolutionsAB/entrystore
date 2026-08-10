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
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedListConfigValidatorTest {

	@Test
	void contiguousIndexedList_doesNotAbort() {
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.password.whitelist.1", "admin")
				.withProperty("entrystore.auth.password.whitelist.2", "user@test.com");

		assertQuiet(environment);
	}

	@Test
	void noConfigurationAtAll_doesNotAbort() {
		assertQuiet(new MockEnvironment());
	}

	@Test
	void indexGap_abortsNamingTheKeyAndTheEntriesThatWouldNowApply() {
		// The legacy reader stopped at the first hole, so .3 was inert; honouring it would widen the list.
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local.1", "cache.internal")
				.withProperty("entrystore.proxy.whitelist.local.3", "metadata.internal");

		String message = assertAborts(environment);

		assertTrue(message.contains("'entrystore.proxy.whitelist.local'"),
				"the diagnostic must name the key; got: " + message);
		assertTrue(message.contains("[3]"),
				"the diagnostic must name the entries that were previously ignored; got: " + message);
	}

	@Test
	void listNotStartingAtOne_abortsAsEntirelyNewlyApplied() {
		// getPropertyValueCount never reached .2 without a .1, so the legacy list was empty.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.permitted.redirects.2", "https://partner.example")
				.withProperty("entrystore.auth.permitted.redirects.3", "https://other.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("[2, 3]"),
				"both entries were previously ignored and must be named; got: " + message);
	}

	@Test
	void zeroIndex_abortsBecauseTheLegacyReaderNeverProbedIt() {
		// getPropertyValueCount started at .1, so .0 was inert; the map binder binds it.
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local.0", "evil.example")
				.withProperty("entrystore.proxy.whitelist.local.1", "cache.internal");

		String message = assertAborts(environment);

		assertTrue(message.contains("[0]"),
				"the newly-applied .0 entry must be named; got: " + message);
	}

	@Test
	void zeroPaddedIndices_abortNamedAsWrittenRatherThanFoldedOntoRealIndices() {
		// The legacy reader looked for the literal key .1, which ".01" is not, so a padded list was inert.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist.01", "example.com")
				.withProperty("entrystore.auth.signup.whitelist.02", "other.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("[01, 02]"),
				"padded suffixes must be reported as written, not as 1 and 2; got: " + message);
	}

	@Test
	void bareValueAlongsideIndexedEntries_abortsWithBothFindings() {
		// The legacy reader used the bare value and ignored .1; the binder does the opposite.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.password.blacklist", "blocked@test.com")
				.withProperty("entrystore.auth.password.blacklist.1", "other@test.com");

		String message = assertAborts(environment);

		// Two facts, reported separately: the bare/indexed clash, and that .1 — which the legacy reader
		// never probed once the bare key existed — is newly applied.
		assertEquals(2, findingCount(message), "got: " + message);
		assertTrue(message.contains("'entrystore.auth.password.blacklist'"),
				"the diagnostics must name the key; got: " + message);
	}

	@Test
	void bareValueOnItsOwn_abortsWithTheRequiredIndexedForm() {
		// This shape would abort the bind anyway, but the BindException names the record, not the key the
		// operator wrote — so the only actionable message is this one.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist", "example.com");

		String message = assertAborts(environment);

		assertTrue(message.contains("entrystore.auth.signup.whitelist.1=<value>"),
				"the diagnostic must show the required indexed form; got: " + message);
	}

	@Test
	void bareValuePlusIndexedEntries_reportsEveryIndexedEntryAsNewlyApplied() {
		// getPropertyValueCount returned 1 as soon as the bare key existed, so it never probed .1 at all —
		// every indexed entry is newly applied here, not just the ones after a hole.
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local", "bare.example")
				.withProperty("entrystore.proxy.whitelist.local.1", "one.example")
				.withProperty("entrystore.proxy.whitelist.local.2", "two.example");

		String message = assertAborts(environment);

		assertEquals(2, findingCount(message), "expected the bare-value clash and the newly-applied entries");
		assertTrue(message.contains("newly applied: [1, 2]"),
				"both indexed entries were previously ignored; got: " + message);
	}

	@Test
	void anonymousProxyWhitelist_isAlsoChecked() {
		// The one INDEXED_LIST_KEYS entry no other case here covers.
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.anonymous.2", "guest.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("entrystore.proxy.whitelist.anonymous"),
				"the diagnostic must name the key; got: " + message);
	}

	@Test
	void indicesAreReportedInNumericOrder() {
		// Lexicographic ordering would render [1, 10, 2], which reads as corruption rather than a gap.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.permitted.redirects.1", "https://one.example")
				.withProperty("entrystore.auth.permitted.redirects.2", "https://two.example")
				.withProperty("entrystore.auth.permitted.redirects.10", "https://ten.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("[1, 2, 10]"),
				"suffixes must be ordered numerically; got: " + message);
	}

	@Test
	void nonNumericSuffix_abortsBecauseItWouldBindAsAnActiveEntry() {
		// Inert under the legacy reader (which probed only the literal .1, .2, ...) but an active list
		// entry under the map binder — a mistyped index must not silently whitelist a host.
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local.l", "metadata.internal")
				.withProperty("entrystore.proxy.whitelist.local.1", "cache.internal");

		String message = assertAborts(environment);

		assertTrue(message.contains("non-numeric index suffixes [l]"),
				"the mistyped suffix must be named; got: " + message);
		assertTrue(message.contains("numeric indices (.1, .2, ...)"),
				"the remedy must state the required form; got: " + message);
	}

	@Test
	void nonNumericBracketedName_abortsToo() {
		// The bracket form binds whatever name it carries, so a named entry is the same hazard.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist[partner]", "partner.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("non-numeric index suffixes [partner]"), "got: " + message);
	}

	@Test
	void nonAsciiDigitSuffix_abortsWithoutCrashing() {
		// StringUtils.isNumeric would accept this and then blow up in BigInteger; the legacy reader could
		// never have matched it, but the map binder binds it — so it is a non-numeric finding, not a crash.
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist.٢", "example.com");

		String message = assertAborts(environment);

		assertTrue(message.contains("non-numeric index suffixes"), "got: " + message);
	}

	@Test
	void environmentVariableSpelling_isDetectedWithBootsDashRemoval() {
		// Boot's SystemEnvironmentPropertyMapper REMOVES dashes: entrystore.proxy.remote-resource.delete.whitelist
		// binds from ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_N. A gap in that spelling must be
		// caught like the dotted form — container deployments are exactly where these shapes appear.
		var environment = new MockEnvironment()
				.withProperty("ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_2", "https://partner.example");

		String message = assertAborts(environment);

		assertTrue(message.contains("'entrystore.proxy.remote-resource.delete.whitelist'"),
				"the diagnostic must name the dotted key; got: " + message);
		assertTrue(message.contains("[2]"), "got: " + message);
	}

	@Test
	void underscoredEnvironmentVariableSpelling_isNotCollected() {
		// ENTRYSTORE_PROXY_REMOTE_RESOURCE_DELETE_WHITELIST_1 is NOT a spelling Spring binds (dashes are
		// removed, not underscored), so treating it as a configured entry would report a list that does
		// not exist. LegacyPropertyKeyDetector-style detection of that dead spelling is a separate concern.
		var environment = new MockEnvironment()
				.withProperty("ENTRYSTORE_PROXY_REMOTE_RESOURCE_DELETE_WHITELIST_1", "https://one.example");

		assertQuiet(environment);
	}

	@Test
	void runsAheadOfTheLegacyKeyDetector() {
		// LegacyPropertyKeyDetector throws on a truthy legacy key at LOWEST_PRECEDENCE; at the same order
		// its throw would suppress every finding in this class on that boot. Still after
		// ConfigDataEnvironmentPostProcessor, so entrystore.properties is loaded when we scan.
		assertEquals(Ordered.LOWEST_PRECEDENCE - 1, new IndexedListConfigValidator().getOrder());
	}

	@Test
	void bareValueWithAGap_reportsBothProblemsSeparately() {
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist", "https://bare.example")
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist.1", "https://one.example")
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist.3", "https://three.example");

		String message = assertAborts(environment);

		assertEquals(2, findingCount(message),
				"the bare-value clash and the gap are separate misconfigurations; got: " + message);
	}

	@Test
	void isRegisteredAsAnEnvironmentPostProcessor() {
		// Loaded the way Boot loads it, so this proves META-INF/spring.factories lists the validator under
		// the EnvironmentPostProcessor key AND that it can be instantiated — not merely that the file
		// contains the expected text. Without the registration every diagnostic above is dead code.
		DeferredLogFactory logFactory = _ -> null;
		var loader = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader());

		List<EnvironmentPostProcessor> processors = loader.load(EnvironmentPostProcessor.class,
				SpringFactoriesLoader.ArgumentResolver.of(DeferredLogFactory.class, logFactory),
				// Boot's own EnvironmentPostProcessors need constructor args this test does not supply;
				// skip them so only processors constructable from the resolver are instantiated.
				(factoryType, factoryImplementationName, failure) -> { });

		assertTrue(processors.stream().anyMatch(IndexedListConfigValidator.class::isInstance),
				"IndexedListConfigValidator must be registered in META-INF/spring.factories; got: "
						+ processors.stream().map(processor -> processor.getClass().getName()).toList());
	}

	private static void assertQuiet(ConfigurableEnvironment environment) {
		assertDoesNotThrow(() -> new IndexedListConfigValidator().postProcessEnvironment(environment, null));
	}

	private static String assertAborts(ConfigurableEnvironment environment) {
		IllegalStateException aborted = assertThrows(IllegalStateException.class,
				() -> new IndexedListConfigValidator().postProcessEnvironment(environment, null));
		assertTrue(aborted.getMessage().startsWith("EntryStore startup aborted"),
				"the exception must lead with the abort header; got: " + aborted.getMessage());
		return aborted.getMessage();
	}

	/** Findings are rendered one per line as {@code "  - <finding>"} bullets. */
	private static long findingCount(String message) {
		return message.lines().filter(line -> line.startsWith("  - ")).count();
	}
}
