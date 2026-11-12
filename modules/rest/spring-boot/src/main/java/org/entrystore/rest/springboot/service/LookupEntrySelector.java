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

package org.entrystore.rest.springboot.service;

import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.impl.ContextManagerImpl;
import org.entrystore.repository.util.URISplit;

import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the entry a lookup answers with when several entries hold the resource URI.
 *
 * <p>Two entries may share a resource URI, e.g. a Link that points at a local entry's resource, while a lookup
 * response carries the metadata and validators of one entry. The pick must not depend on iteration order, which a
 * caller can influence by creating entries. The rule, in order:
 * <ol>
 * <li>entries whose metadata the current user may read, if there is any;</li>
 * <li>the Local entry that owns the URI, i.e. the entry of this repository the URI belongs to;</li>
 * <li>the lowest entry URI, compared as a string.</li>
 * </ol>
 */
final class LookupEntrySelector {

	private LookupEntrySelector() {
	}

	/**
	 * @return the entry to answer with, or empty if there is no holder; an entry the current user may not read is
	 * returned if the user may read none of the holders, so the caller must check access itself
	 */
	static Optional<Entry> select(Collection<Entry> holders, URI resourceURI) {
		if (holders.isEmpty()) {
			return Optional.empty();
		}
		URL repositoryURL = holders.iterator().next().getRepositoryManager().getRepositoryURL();
		Optional<URI> ownerEntryURI = URISplit.entryURIOf(resourceURI, repositoryURL);

		List<Entry> readable = holders.stream().filter(ContextManagerImpl::isEntryMetadataReadable).toList();
		Collection<Entry> candidates = readable.isEmpty() ? holders : readable;

		Comparator<Entry> ownerFirst = Comparator.comparing(entry -> !isOwner(entry, ownerEntryURI));
		return candidates.stream().min(ownerFirst.thenComparing(entry -> entry.getEntryURI().toString()));
	}

	/** Whether {@code entry} is the Local entry of this repository that the resource URI belongs to. */
	private static boolean isOwner(Entry entry, Optional<URI> ownerEntryURI) {
		return entry.getEntryType() == EntryType.Local && ownerEntryURI.filter(entry.getEntryURI()::equals).isPresent();
	}

}
