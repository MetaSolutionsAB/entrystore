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
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexedListConfigValidatorTest {

	@Test
	void contiguousIndexedList_isNotReported() {
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.password.whitelist.1", "admin")
				.withProperty("entrystore.auth.password.whitelist.2", "user@test.com");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertTrue(errors.isEmpty(), "a correctly numbered list must not be reported; got: " + errors);
	}

	@Test
	void noConfigurationAtAll_isNotReported() {
		var errors = new ArrayList<String>();

		newValidator(errors).postProcessEnvironment(new MockEnvironment(), null);

		assertTrue(errors.isEmpty(), "an unset key must not be reported; got: " + errors);
	}

	@Test
	void indexGap_isReportedNamingTheKeyAndTheEntriesThatNowApply() {
		// The legacy reader stopped at the first hole, so .3 was inert; it now applies.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local.1", "cache.internal")
				.withProperty("entrystore.proxy.whitelist.local.3", "metadata.internal");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("'entrystore.proxy.whitelist.local'"),
				"the diagnostic must name the key; got: " + errors.getFirst());
		assertTrue(errors.getFirst().contains("[3]"),
				"the diagnostic must name the entries that were previously ignored; got: " + errors.getFirst());
	}

	@Test
	void listNotStartingAtOne_isReportedAsEntirelyNewlyApplied() {
		// getPropertyValueCount never reached .2 without a .1, so the legacy list was empty.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.permitted.redirects.2", "https://partner.example")
				.withProperty("entrystore.auth.permitted.redirects.3", "https://other.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("[2, 3]"),
				"both entries were previously ignored and must be named; got: " + errors.getFirst());
	}

	@Test
	void zeroIndex_isReportedBecauseTheLegacyReaderNeverProbedIt() {
		// getPropertyValueCount started at .1, so .0 was inert; the map binder binds it.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local.0", "evil.example")
				.withProperty("entrystore.proxy.whitelist.local.1", "cache.internal");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("[0]"),
				"the newly-applied .0 entry must be named; got: " + errors.getFirst());
	}

	@Test
	void zeroPaddedIndices_areReportedAsWrittenRatherThanFoldedOntoRealIndices() {
		// The legacy reader looked for the literal key .1, which ".01" is not, so a padded list was inert.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist.01", "example.com")
				.withProperty("entrystore.auth.signup.whitelist.02", "other.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("[01, 02]"),
				"padded suffixes must be reported as written, not as 1 and 2; got: " + errors.getFirst());
	}

	@Test
	void bareValueAlongsideIndexedEntries_isReported() {
		// The legacy reader used the bare value and ignored .1; the binder does the opposite.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.password.blacklist", "blocked@test.com")
				.withProperty("entrystore.auth.password.blacklist.1", "other@test.com");

		newValidator(errors).postProcessEnvironment(environment, null);

		// Two facts, reported separately: the bare/indexed clash, and that .1 — which the legacy reader
		// never probed once the bare key existed — is newly applied.
		assertEquals(2, errors.size(), "got: " + errors);
		assertTrue(errors.stream().allMatch(msg -> msg.contains("'entrystore.auth.password.blacklist'")),
				"both diagnostics must name the key; got: " + errors);
	}

	@Test
	void bareValueOnItsOwn_isReportedWithTheRequiredIndexedForm() {
		// This shape aborts the bind, but the BindException names the record, not the key the operator
		// wrote — so the only actionable message is this one.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist", "example.com");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("entrystore.auth.signup.whitelist.1=<value>"),
				"the diagnostic must show the required indexed form; got: " + errors.getFirst());
	}

	@Test
	void bareValuePlusIndexedEntries_reportsEveryIndexedEntryAsNewlyApplied() {
		// getPropertyValueCount returned 1 as soon as the bare key existed, so it never probed .1 at all —
		// every indexed entry is newly applied here, not just the ones after a hole.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.local", "bare.example")
				.withProperty("entrystore.proxy.whitelist.local.1", "one.example")
				.withProperty("entrystore.proxy.whitelist.local.2", "two.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(2, errors.size(), "expected the bare-value clash and the newly-applied entries");
		assertTrue(errors.stream().anyMatch(message -> message.contains("newly applied: [1, 2]")),
				"both indexed entries were previously ignored; got: " + errors);
	}

	@Test
	void anonymousProxyWhitelist_isAlsoChecked() {
		// The one INDEXED_LIST_KEYS entry no other case here covers.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.whitelist.anonymous.2", "guest.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("entrystore.proxy.whitelist.anonymous"),
				"the diagnostic must name the key; got: " + errors.getFirst());
	}

	@Test
	void indicesAreReportedInNumericOrder() {
		// Lexicographic ordering would render [1, 10, 2], which reads as corruption rather than a gap.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.permitted.redirects.1", "https://one.example")
				.withProperty("entrystore.auth.permitted.redirects.2", "https://two.example")
				.withProperty("entrystore.auth.permitted.redirects.10", "https://ten.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(1, errors.size());
		assertTrue(errors.getFirst().contains("[1, 2, 10]"),
				"suffixes must be ordered numerically; got: " + errors.getFirst());
	}

	@Test
	void nonAsciiDigitSuffix_isIgnoredRatherThanCrashing() {
		// StringUtils.isNumeric would accept this and then blow up in BigInteger; the legacy reader could
		// never have matched it either.
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.auth.signup.whitelist.٢", "example.com");

		assertDoesNotThrow(() -> newValidator(errors).postProcessEnvironment(environment, null));
		assertTrue(errors.isEmpty(), "a non-index suffix is not an indexed entry; got: " + errors);
	}

	@Test
	void runsAheadOfTheLegacyKeyDetector() {
		// LegacyPropertyKeyDetector throws on a truthy legacy key at LOWEST_PRECEDENCE; at the same order
		// its throw would suppress every diagnostic in this class on that boot. Still after
		// ConfigDataEnvironmentPostProcessor, so entrystore.properties is loaded when we scan.
		assertEquals(Ordered.LOWEST_PRECEDENCE - 1, newValidator(new ArrayList<>()).getOrder());
	}

	@Test
	void bareValueWithAGap_reportsBothProblemsSeparately() {
		var errors = new ArrayList<String>();
		var environment = new MockEnvironment()
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist", "https://bare.example")
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist.1", "https://one.example")
				.withProperty("entrystore.proxy.remote-resource.delete.whitelist.3", "https://three.example");

		newValidator(errors).postProcessEnvironment(environment, null);

		assertEquals(2, errors.size(),
				"the bare-value clash and the gap are separate misconfigurations; got: " + errors);
	}

	@Test
	void isRegisteredAsAnEnvironmentPostProcessor() {
		// Loaded the way Boot loads it, so this proves META-INF/spring.factories lists the validator under
		// the EnvironmentPostProcessor key AND that it can be instantiated from a DeferredLogFactory —
		// not merely that the file contains the expected text. Without the registration every diagnostic
		// above is dead code.
		DeferredLogFactory logFactory = _ -> new RecordingLog(new ArrayList<>());
		var loader = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader());

		List<EnvironmentPostProcessor> processors = loader.load(EnvironmentPostProcessor.class,
				SpringFactoriesLoader.ArgumentResolver.of(DeferredLogFactory.class, logFactory),
				// Boot's own EnvironmentPostProcessors need constructor args this test does not supply;
				// skip them so only processors constructable from a DeferredLogFactory are instantiated.
				(factoryType, factoryImplementationName, failure) -> { });

		assertTrue(processors.stream().anyMatch(IndexedListConfigValidator.class::isInstance),
				"IndexedListConfigValidator must be registered in META-INF/spring.factories; got: "
						+ processors.stream().map(processor -> processor.getClass().getName()).toList());
	}

	private static IndexedListConfigValidator newValidator(List<String> errorSink) {
		return new IndexedListConfigValidator(_ -> new RecordingLog(errorSink));
	}

	/**
	 * Minimal {@link Log} that records single-argument {@code error} calls. The validator only ever emits
	 * via single-argument ERROR; every other level and the two-argument {@code error} overload fail loudly
	 * so an unexpected production log call surfaces as a test failure instead of being silently dropped.
	 */
	private static final class RecordingLog implements Log {

		private final List<String> errors;

		RecordingLog(List<String> errors) {
			this.errors = errors;
		}

		@Override
		public void error(Object message) {
			errors.add(String.valueOf(message));
		}

		@Override
		public boolean isErrorEnabled() {
			return true;
		}

		// All true, so a guarded call such as `if (log.isWarnEnabled()) log.warn(...)` still reaches the
		// throw below instead of being silently skipped — which is the outcome this double exists to prevent.
		@Override
		public boolean isDebugEnabled() {
			return true;
		}

		@Override
		public boolean isFatalEnabled() {
			return true;
		}

		@Override
		public boolean isInfoEnabled() {
			return true;
		}

		@Override
		public boolean isTraceEnabled() {
			return true;
		}

		@Override
		public boolean isWarnEnabled() {
			return true;
		}

		@Override
		public void error(Object message, Throwable t) {
			throw unexpected("error(Object, Throwable)", message);
		}

		@Override
		public void debug(Object message) {
			throw unexpected("debug", message);
		}

		@Override
		public void debug(Object message, Throwable t) {
			throw unexpected("debug", message);
		}

		@Override
		public void fatal(Object message) {
			throw unexpected("fatal", message);
		}

		@Override
		public void fatal(Object message, Throwable t) {
			throw unexpected("fatal", message);
		}

		@Override
		public void info(Object message) {
			throw unexpected("info", message);
		}

		@Override
		public void info(Object message, Throwable t) {
			throw unexpected("info", message);
		}

		@Override
		public void trace(Object message) {
			throw unexpected("trace", message);
		}

		@Override
		public void trace(Object message, Throwable t) {
			throw unexpected("trace", message);
		}

		@Override
		public void warn(Object message) {
			throw unexpected("warn", message);
		}

		@Override
		public void warn(Object message, Throwable t) {
			throw unexpected("warn", message);
		}

		private static AssertionError unexpected(String level, Object message) {
			return new AssertionError("unexpected " + level + " log call: " + message);
		}
	}
}
