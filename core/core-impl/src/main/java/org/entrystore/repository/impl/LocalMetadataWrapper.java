/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

package org.entrystore.repository.impl;

import java.net.URI;

import org.entrystore.Entry;
import org.entrystore.Metadata;
import org.openrdf.model.Graph;
import org.openrdf.model.impl.GraphImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LocalMetadataWrapper implements Metadata {

	Logger log = LoggerFactory.getLogger(LocalMetadataWrapper.class);

	Entry entry;

	URI refEntryURI;

	public LocalMetadataWrapper(Entry entry) {
		this.entry = entry;
		Entry e = entry.getRepositoryManager().getContextManager().getEntry(entry.getExternalMetadataURI());
		if (e != null) {
			refEntryURI = e.getEntryURI();
		} else {
			log.warn("Could not find Entry with external MD URI: " + entry.getExternalMetadataURI());
		}
	}

	public Graph getGraph() {
		Entry e = null;
		if (refEntryURI != null) {
			e = ((ContextImpl) entry.getContext()).getCache().getByEntryURI(refEntryURI);
		}
		if (e == null) {
			e = entry.getRepositoryManager().getContextManager().getEntry(entry.getExternalMetadataURI());
		}
		if (e != null && e.getLocalMetadata() != null) {
			return e.getLocalMetadata().getGraph();
		} else {
			log.warn("Entry is null, returning an empty graph");
			return new GraphImpl();
		}
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

	public void setGraph(Graph graph) {
		throw new UnsupportedOperationException();
	}

}