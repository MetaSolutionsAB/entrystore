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

package org.entrystore.rest.resources;

import org.entrystore.Entity;
import org.entrystore.EntryType;
import org.entrystore.GraphEntity;
import org.entrystore.Metadata;
import org.entrystore.Provenance;
import org.entrystore.ProvenanceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;


/**
 * Access to local metadata.
 * 
 * @author Hannes Ebner
 */
public class LocalMetadataResource extends AbstractMetadataResource {

	static Logger log = LoggerFactory.getLogger(LocalMetadataResource.class);

	@Override
	protected Metadata getMetadata() {
		if (entry != null) {
			EntryType et = entry.getEntryType();
			if (EntryType.Local.equals(et) || EntryType.Link.equals(et) || EntryType.LinkReference.equals(et)) {
				Provenance provenance = entry.getProvenance();
				if (this.parameters.containsKey("rev") && provenance != null) {
					Entity entity = provenance.getEntityFor(parameters.get("rev"), ProvenanceType.Metadata);
					if (entity instanceof GraphEntity) {
						return ((GraphEntity) entity);
					}
					return null;
				}
				return entry.getLocalMetadata();
			}
		}
		return null;
	}

}