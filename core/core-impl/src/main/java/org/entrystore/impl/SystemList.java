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
import org.openrdf.repository.RepositoryConnection;


public class SystemList extends ListImpl {
	
	public SystemList(EntryImpl entry, String uri) {
		super(entry, uri);
	}
	
	public SystemList(EntryImpl entry, org.openrdf.model.URI uri) {
		super(entry, uri);
	}

	public void addChild(URI child) {
		throw new UnsupportedOperationException();
	}

	public java.util.List<URI> getChildren() {
		throw new UnsupportedOperationException();
	}

	public void moveChildAfter(URI child, URI afterChild) {
		throw new UnsupportedOperationException();
	}

	public void moveChildBefore(URI child, URI beforeChild) {
		throw new UnsupportedOperationException();
	}

	public boolean removeChild(URI child) {
		throw new UnsupportedOperationException();
	}

	public void remove(RepositoryConnection rc) throws Exception {
		throw new UnsupportedOperationException();
	}

	public boolean isRemovable() {
		return false;
	}

	public void removeTree(RepositoryConnection rc) {
		throw new UnsupportedOperationException();
	}

	public Entry moveEntryHere(URI entry, URI fromList) {
		throw new UnsupportedOperationException();
	}
}