/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore;

import org.eclipse.rdf4j.model.Model;

import java.net.URI;
import java.util.Date;
import java.util.List;


/**
 * Maintains the provenance, that is, a revision history for various parts
 * of an entry according to the prov ontology.
 * The revisions are recorded as entities, currently only metadata entities
 * are supported, but support for revisions to the resource and the
 * entry information may be added.
 *
 * The expression is encoded in the entry graph where each entity is described
 * with the following properties:
 * <ul>
 *     <li>prov:wasRevisionOf - pointing to the previous revision (if any)</li>
 *     <li>prov:wasAttributedTo - pointing to the resource URI of the user causing the revision</li>
 *     <li>prov:generatedAtTime - providing the date of change</li>
 * </ul>
 *
 * The latest metadata entity (revision) points to the current metadata URI
 * via owl:sameAs. The URI of each metadata entity is constructed from the local
 * metadata URI with a "?rev=nr" appended where nr is 1 for the first revision
 * and then incremented for each revision.
 *
 * @author Matthias Palmér
 * @see <a href="https://www.w3.org/TR/prov-o/">https://www.w3.org/TR/prov-o/</a>
 */
public interface Provenance {

	/**
	 * Provides a list of entities involved in the provenance of this Entry, may be filtered by a ProvenanceType.
	 *
	 * @param type optional filter to get a list of entities of a certain type.
	 * @return a list of entities
	 */
	List<Entity> getEntities(ProvenanceType type);

	/**
	 * Find the latest relevant entity of a certain date.
	 *
	 * @param date the date to look for
	 * @param type the type of entity to look for
	 * @return the latest relevant entity before this date of the given type
	 */
	Entity getEntityAt(Date date, ProvenanceType type);

	/**
	 * Finds the entity with the given URI.
	 * @param uri
	 * @return an activity
	 */
	Entity getEntityFor(URI uri);

	/**
	 * Finds the entity for the given revision and provenance type.
	 * @param revision
	 * @param type
	 * @return
	 */
	Entity getEntityFor(String revision, ProvenanceType type);

	/**
	 * Adds a new metadata entity, since the new metadata will be
	 * in the current repository, we provide only the graph before this revision,
	 * to be stored in the previous metadata entity.
	 * @param previousRevisionGraph the graph corresponding to how the metadata looked
	 *                    before this revision (the previous revision).
	 * @return a new GraphEntity
	 */
	GraphEntity addMetadataEntity(Model previousRevisionGraph);
}