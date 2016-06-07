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

package org.entrystore.impl;

import java.net.URI;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Entry;
import org.entrystore.repository.RepositoryException;
import org.entrystore.ResourceType;


/**
 * @author matthias
 *
 */
public class RegularContext extends ContextImpl{
	private static Log log = LogFactory.getLog(RegularContext.class);

	/**
	 * Creates a principal manager
	 * @param entry this principal managers entry
	 * @param uri this principal managers URI 
	 * @param cache
	 */
	public RegularContext(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri, cache);
	}

	@Override
	public Entry createResource(String entryId, GraphType buiType,
			ResourceType repType, URI listURI) {
		switch (buiType) {
		case List:
		case ResultList:
		case Graph:  //TODO: Check if OK!!!
		case String:
		case None:
		case Pipeline:
		case PipelineResult:
			return super.createResource(entryId, buiType, repType, listURI);			
		default:
			throw new RepositoryException("Regular context only support Lists, ResultLists and None as BuiltinTypes");
		}
	}

	public void initializeSystemEntries() {
		super.initializeSystemEntries();
		Entry entry;

		Entry comments = get("_comments");
		if(comments == null) {
			comments = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_comments");
			setMetadata(comments, "Comments folder", null);
			log.info("Successfully added the comments list");
		} 
		addSystemEntryToSystemEntries(comments.getEntryURI());

		entry = get("_top");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_top");
			setMetadata(entry, "Top folder", null);
			log.info("Successfully added the top list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());

		entry = get("_contacts");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_contacts");
			setMetadata(entry, "Contacts", "A list of all your contacts.");
			log.info("Successfully added the contact list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());

		entry = get("_featured");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_featured");
			setMetadata(entry, "Featured", "A list of featured content divided into sublists.");
			log.info("Successfully added the featured list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());

		entry = get("_feeds");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_feeds");
			setMetadata(entry, "Feeds", "A list of feeds.");
			log.info("Successfully added the feeds list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());

/*		entry = get("_ontologies");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, ResourceType.List, null, "_ontologies");
			setMetadata(entry, "Ontologies", null);
			log.info("Successfully added the ontologies list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());

		entry = get("_types");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, ResourceType.List, null, "_types");
			setMetadata(entry, "Types", null);
			log.info("Successfully added the types list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());
*/
		entry = get("_trash");
		if(entry == null) {
			entry = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_trash");
			setMetadata(entry, "Garbage bin", null);
			log.info("Successfully added the trash list");
		}
		addSystemEntryToSystemEntries(entry.getEntryURI());
	}

}