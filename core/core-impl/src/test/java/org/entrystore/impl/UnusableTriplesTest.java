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

package org.entrystore.impl;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.impl.ContextManagerImpl.UnusableTriples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Sampled objects come straight from the store, where a caller holding WriteMetadata can put a
 * literal of any size and content under an index predicate, so these pin the bounds that keep such
 * a literal from flooding or forging log records.
 */
public class UnusableTriplesTest {

	private static final ValueFactory VF = SimpleValueFactory.getInstance();

	@Test
	public void countsEverySkipButSamplesOnlyTheFirstFive() {
		UnusableTriples unusable = new UnusableTriples();

		for (int i = 0; i < 12; i++) {
			unusable.skipped(VF.createIRI("http://example.com/" + i), "resolves to no entry");
		}

		assertEquals(12, unusable.getCount());
		assertEquals(5, unusable.getSample().size());
		assertTrue(unusable.getSample().getFirst().startsWith("http://example.com/0"));
	}

	@Test
	public void truncatesALongLiteralButKeepsTheReason() {
		UnusableTriples unusable = new UnusableTriples();

		unusable.skipped(VF.createLiteral("x".repeat(10_000)), "not an IRI");

		String sampled = unusable.getSample().getFirst();
		// the object is cut, but the reason is what tells an operator why it was skipped
		assertTrue(sampled.contains("..."));
		assertTrue(sampled.endsWith("(not an IRI)"));
		assertTrue(sampled.length() < 300);
	}

	@Test
	public void truncatesALongReasonToo() {
		UnusableTriples unusable = new UnusableTriples();

		// URI.create echoes the whole offending IRI in its message
		unusable.skipped(VF.createIRI("http://example.com/x"), "IllegalArgumentException: " + "y".repeat(10_000));

		assertTrue(unusable.getSample().getFirst().length() < 500);
	}

	@Test
	public void stripsControlCharactersSoALiteralCannotForgeALogRecord() {
		String escape = String.valueOf((char) 0x1B);
		String lineSeparator = String.valueOf((char) 0x2028);
		UnusableTriples unusable = new UnusableTriples();

		unusable.skipped(VF.createLiteral("a\r\n" + escape + "[31m" + lineSeparator
			+ "2026-09-15 WARN forged record"), "not an IRI");

		String sampled = unusable.getSample().getFirst();
		assertFalse(sampled.contains("\r"));
		assertFalse(sampled.contains("\n"));
		assertFalse(sampled.contains(escape));
		assertFalse(sampled.contains(lineSeparator));
		assertTrue(sampled.contains("forged record"));
	}

	// the offsets around the cut, so the pair straddles it in at least one row
	@ParameterizedTest(name = "prefix {0}")
	@ValueSource(ints = {195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205})
	public void neverCutsASurrogatePairInHalf(int prefix) {
		UnusableTriples unusable = new UnusableTriples();

		unusable.skipped(VF.createLiteral("z".repeat(prefix) + Character.toString(0x1F600) + "z".repeat(50)),
			"not an IRI");

		String sampled = unusable.getSample().getFirst();
		for (int i = 0; i < sampled.length(); i++) {
			boolean loneSurrogate = Character.isHighSurrogate(sampled.charAt(i))
				&& (i + 1 == sampled.length() || !Character.isLowSurrogate(sampled.charAt(i + 1)));
			assertFalse(loneSurrogate, "lone surrogate at " + i);
		}
	}

	@Test
	public void samplesTheReasonDerivedFromACause() {
		UnusableTriples unusable = new UnusableTriples();

		for (int i = 0; i < 7; i++) {
			unusable.skipped(VF.createIRI("http://example.com/" + i),
				new IllegalArgumentException("boom").toString());
		}

		assertEquals(7, unusable.getCount());
		assertEquals(5, unusable.getSample().size());
		assertTrue(unusable.getSample().getFirst().contains("boom"));
	}

	@Test
	public void warnsNothingWhenNoTripleWasSkipped() {
		Logger log = mock(Logger.class);

		new UnusableTriples().warn(log, RepositoryProperties.resHasEntry, URI.create("http://example.com/r"));

		verifyNoInteractions(log);
	}

	@Test
	public void warnReportsTheTotalAndHowManyItSampled() {
		Logger log = mock(Logger.class);
		UnusableTriples unusable = new UnusableTriples();
		for (int i = 0; i < 7; i++) {
			unusable.skipped(VF.createIRI("http://example.com/" + i), "resolves to no entry");
		}

		unusable.warn(log, RepositoryProperties.resHasEntry, URI.create("http://example.com/r"));

		// the "first {}" form, since more was skipped than could be sampled
		verify(log).warn(contains("first"), eq(7), any(), any(), eq(5), any());
	}

	@Test
	public void warnDoesNotClaimATruncationWhenEverySkipWasSampled() {
		Logger log = mock(Logger.class);
		UnusableTriples unusable = new UnusableTriples();
		for (int i = 0; i < 3; i++) {
			unusable.skipped(VF.createIRI("http://example.com/" + i), "resolves to no entry");
		}

		unusable.warn(log, RepositoryProperties.resHasEntry, URI.create("http://example.com/r"));

		verify(log).warn(argThat((String message) -> !message.contains("first")), eq(3), any(), any(), any());
	}
}
