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

package org.entrystore;

import org.openrdf.model.Graph;

import java.net.URI;
import java.util.Date;
import java.util.List;


/**
 * @author Matthias Palmér
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
	 * Adds a new entity Metadata, since the new metadata will be
	 * in the current repository, we provide only the old graph,
	 * to be stored in the previous metadata activity.
	 * @param oldgraph the metadata graph corresponding to how the metadata looked before the change.
	 * @return a new GraphEntity
	 */
	GraphEntity addMetadataEntity(Graph oldgraph);
}