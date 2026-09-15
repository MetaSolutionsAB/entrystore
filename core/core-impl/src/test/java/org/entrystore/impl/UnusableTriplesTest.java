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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	public void truncatesALongLiteralSoOneObjectCannotFillTheLogLine() {
		UnusableTriples unusable = new UnusableTriples();

		unusable.skipped(VF.createLiteral("x".repeat(10_000)), "not an IRI");

		// 200 chars of object plus the ellipsis, never the whole literal
		assertEquals(203, unusable.getSample().getFirst().length());
		assertTrue(unusable.getSample().getFirst().endsWith("..."));
	}

	@Test
	public void stripsLineBreaksSoALiteralCannotForgeALogRecord() {
		UnusableTriples unusable = new UnusableTriples();

		unusable.skipped(VF.createLiteral("a\r\n2026-09-11 WARN forged record"), "not an IRI");

		String sampled = unusable.getSample().getFirst();
		assertFalse(sampled.contains("\r"));
		assertFalse(sampled.contains("\n"));
		assertTrue(sampled.contains("forged record"));
	}

	@Test
	public void rendersTheCauseOnlyWhileItCanStillBeSampled() {
		UnusableTriples unusable = new UnusableTriples();

		for (int i = 0; i < 7; i++) {
			unusable.skipped(VF.createIRI("http://example.com/" + i), new IllegalArgumentException("boom"));
		}

		assertEquals(7, unusable.getCount());
		assertEquals(5, unusable.getSample().size());
		assertTrue(unusable.getSample().getFirst().contains("boom"));
	}
}
