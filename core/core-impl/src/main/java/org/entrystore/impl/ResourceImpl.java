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


package org.entrystore.impl;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Entry;
import org.entrystore.Resource;

import java.net.URI;


public class ResourceImpl implements Resource {

	protected EntryImpl entry;
	protected IRI resourceURI;
	
	protected ResourceImpl(EntryImpl entry, String resourceURI) {
		this.entry = entry;
		this.resourceURI = entry.repository.getValueFactory().createIRI(resourceURI.toString());
	}
	
	public ResourceImpl(EntryImpl entry, IRI resourceURI) {
		this.entry = entry;
		this.resourceURI = resourceURI;
	}

	public URI getURI() {
		return URI.create(resourceURI.toString());
	}
	
	public IRI getSesameURI() {
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