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

import org.entrystore.Entry;
import org.entrystore.Resource;
import org.openrdf.repository.RepositoryConnection;


public class ResourceImpl implements Resource {

	protected EntryImpl entry;
	protected org.openrdf.model.URI resourceURI;
	
	protected ResourceImpl(EntryImpl entry, String resourceURI) {
		this.entry = entry;
		this.resourceURI = entry.repository.getValueFactory().createURI(resourceURI.toString());
	}
	
	public ResourceImpl(EntryImpl entry, org.openrdf.model.URI resourceURI) {
		this.entry = entry;
		this.resourceURI = resourceURI;
	}

	public URI getURI() {
		return URI.create(resourceURI.toString());
	}
	
	public org.openrdf.model.URI getSesameURI() {
		return resourceURI;
	}
	
	public Entry getEntry() {
		return this.entry;
	}
		
	public void remove(RepositoryConnection rc) throws Exception {
	}

	public boolean isRemovable() {
		return true;
	}

}