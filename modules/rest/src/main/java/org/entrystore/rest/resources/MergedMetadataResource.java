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

package org.entrystore.rest.resources;

import org.eclipse.rdf4j.model.Model;
import org.entrystore.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Date;
import java.util.Objects;


/**
 * Access to a merged graph including local and cached external metadata.
 * 
 * @author Hannes Ebner
 */
public class MergedMetadataResource extends AbstractMetadataResource {

	private static Logger log = LoggerFactory.getLogger(MergedMetadataResource.class);

	@Override
	protected Metadata getMetadata() {
		// cannot be used since we don't have a metadata object for the merged metadata
		return null;
	}

	@Override
	protected Model getMetadataGraph() {
		return entry.getMetadataGraph();
	}

	@Override
	protected Date getModificationDate() {
		return latest(entry.getExternalMetadataCacheDate(), entry.getModifiedDate());
	}

	private Date latest(Date... dates) {
		return Arrays.stream(dates).filter(Objects::nonNull).max(Date::compareTo).orElse(null);
	}

}