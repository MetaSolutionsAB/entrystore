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

import org.entrystore.EntryType;
import org.entrystore.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Access to cached external metadata.
 * 
 * @author Hannes Ebner
 */
public class ExternalMetadataResource extends AbstractMetadataResource {

	static Logger log = LoggerFactory.getLogger(ExternalMetadataResource.class);

	protected Metadata getMetadata() {
		if (entry != null) {
			EntryType et = entry.getEntryType();
			if (EntryType.Reference.equals(et) || EntryType.LinkReference.equals(et)) {
				return entry.getCachedExternalMetadata();
			}
		}
		return null;
	}

}