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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.Entry;
import org.entrystore.Metadata;
import org.entrystore.repository.util.CorruptData;
import org.entrystore.repository.util.URISplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;


public class LocalMetadataWrapper implements Metadata {

	Logger log = LoggerFactory.getLogger(LocalMetadataWrapper.class);

	Entry entry;

	/**
	 * Does not resolve the referenced entry: the wrapper is created while its entry is being loaded, and
	 * resolving a referenced entry that refers back to the entry being loaded would load that entry again
	 * without end. This must stay lazy even though external metadata URIs of the entry itself are rejected (see
	 * {@link EntryImpl#checkExternalMetadataURI}): that check does not detect every URI that denotes the entry.
	 */
	public LocalMetadataWrapper(Entry entry) {
		this.entry = entry;
	}

	/**
	 * @return the local metadata of the referenced entry, or an empty graph if the external metadata URI does not
	 * denote an entry, there is no such entry, it has no local metadata, or it cannot be loaded because its data or
	 * that of its context entry is corrupt; a reference is then treated like a reference to a missing entry
	 */
	public Model getGraph() {
		URI externalMetadataURI = entry.getExternalMetadataURI();
		URI refEntryURI = getReferencedEntryURI();
		if (refEntryURI == null) {
			log.warn("External metadata URI {} of entry {} does not denote an entry, returning an empty graph",
					externalMetadataURI, entry.getEntryURI());
			return new LinkedHashModel();
		}
		Entry e = ((ContextImpl) entry.getContext()).getSoftCache().getByEntryURI(refEntryURI);
		if (e == null) {
			try {
				e = entry.getRepositoryManager().getContextManager().getEntry(refEntryURI);
			} catch (RuntimeException failure) {
				URL repositoryURL = entry.getRepositoryManager().getRepositoryURL();
				if (!CorruptData.isCorruptDataOf(failure, refEntryURI, repositoryURL)) {
					throw failure;
				}
				log.warn("Entry {} that entry {} refers to with external metadata URI {} cannot be loaded, returning"
						+ " an empty graph: {}", refEntryURI, entry.getEntryURI(), externalMetadataURI,
						CorruptData.describe(failure));
				return new LinkedHashModel();
			}
		}
		if (e == null) {
			log.warn("Entry {} that entry {} refers to with external metadata URI {} does not exist, returning an "
					+ "empty graph", refEntryURI, entry.getEntryURI(), externalMetadataURI);
			return new LinkedHashModel();
		}
		if (e.getLocalMetadata() == null) {
			log.warn("Entry {} that entry {} refers to with external metadata URI {} has no local metadata, "
					+ "returning an empty graph", refEntryURI, entry.getEntryURI(), externalMetadataURI);
			return new LinkedHashModel();
		}
		return e.getLocalMetadata().getGraph();
	}

	/**
	 * @return the URI of the entry that the external metadata URI belongs to, or null if the URI does not
	 * belong to an entry of this repository
	 */
	public URI getReferencedEntryURI() {
		return URISplit.entryURIOf(entry.getExternalMetadataURI(), entry.getRepositoryManager().getRepositoryURL())
				.orElse(null);
	}

	public URI getResourceURI() {
		return entry.getResourceURI();
	}

	public URI getURI() {
		return entry.getExternalMetadataURI();
	}

	public boolean isCached() {
		return true;
	}

	public void setGraph(Model graph) {
		throw new UnsupportedOperationException();
	}

}
