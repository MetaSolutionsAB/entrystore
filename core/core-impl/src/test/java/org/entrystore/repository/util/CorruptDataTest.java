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

package org.entrystore.repository.util;

import org.entrystore.repository.CorruptEntryException;
import org.entrystore.repository.RepositoryException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CorruptDataTest {

	private static final URI ENTRY = URI.create("http://localhost:8181/1/entry/2");
	private static final URI CONTEXT_ENTRY = URI.create("http://localhost:8181/_contexts/entry/1");
	private static final URI OTHER_ENTRY = URI.create("http://localhost:8181/_principals/entry/5");

	private static URL repositoryURL() throws Exception {
		return URI.create("http://localhost:8181/").toURL();
	}

	private static RepositoryException loadFailure(URI corruptEntryURI) {
		return new RepositoryException("Unable to load entry " + ENTRY,
				new CorruptEntryException(corruptEntryURI, "Entry graph <" + corruptEntryURI + "> is corrupt"));
	}

	@Test
	public void corruptDataOfTheEntryItself() throws Exception {
		assertTrue(CorruptData.isCorruptDataOf(loadFailure(ENTRY), ENTRY, repositoryURL()));
	}

	@Test
	public void corruptDataOfTheContextEntry() throws Exception {
		assertTrue(CorruptData.isCorruptDataOf(loadFailure(CONTEXT_ENTRY), ENTRY, repositoryURL()));
	}

	@Test
	public void corruptDataOfAnotherEntry() throws Exception {
		assertFalse(CorruptData.isCorruptDataOf(loadFailure(OTHER_ENTRY), ENTRY, repositoryURL()));
	}

	@Test
	public void failureNotCausedByCorruptData() throws Exception {
		assertFalse(CorruptData.isCorruptDataOf(new RepositoryException("Failed to connect"), ENTRY, repositoryURL()));
	}

	@Test
	public void nullEntryNeverMatches() throws Exception {
		assertFalse(CorruptData.isCorruptDataOf(loadFailure(null), null, repositoryURL()));
	}

	@Test
	public void entryUriThatCannotBeSplitMatchesOnlyItself() throws Exception {
		URI unsplittable = URI.create("http://localhost:8181/1/entry");

		assertTrue(CorruptData.isCorruptDataOf(loadFailure(unsplittable), unsplittable, repositoryURL()));
		assertFalse(CorruptData.isCorruptDataOf(loadFailure(CONTEXT_ENTRY), unsplittable, repositoryURL()));
	}

}
