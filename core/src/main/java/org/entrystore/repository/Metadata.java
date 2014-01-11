/**
 * Copyright (c) 2007-2010
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

package org.entrystore.repository;

import java.net.URI;

import org.openrdf.model.Graph;

/**
 * Represents an RDF graph (Sesame {@link Graph}) for the metadata.
 * If {@link EntryType} is {@link EntryType#Reference} the RDF
 * graph is a cache of an RDF graph found outside of the repository.
 * 
 * @author matthias
 */
public interface Metadata {
	
	/**
	 * @return the described resources URI.
	 */
	URI getResourceURI();
	
	/**
	 * @return URI within the context for the metadata, if reference 
	 * ({@link Entry#isReference()}) the URI for the cached
	 * metadata in the repository is returned, not the URI to the original 
	 * metadata. Same as {@link Entry#getLocalMetadataURI()}.
	 * 
	 * @see Entry#getLocalMetadataURI()
	 * @see Entry#getExternalMetadataURI()
	 */
	URI getURI();
	
	/**
	 * @return a RDF graph (Sesame {@link Graph}) for the metadata.
	 */
	Graph getGraph();

	/**
	 * Does not work for cached metadata, i.e. check if
	 * ({@link Entry#getEntryType()}) returns {@link EntryType#Reference}.
	 * @param replace the old metadata with the new {@link Graph}. 
	 */
	void setGraph(Graph graph);
	
	public boolean isCached(); 
}