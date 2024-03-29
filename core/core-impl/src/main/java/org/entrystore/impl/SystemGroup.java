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
import org.entrystore.Context;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.User;

import java.net.URI;
import java.util.List;
import java.util.Vector;


public class SystemGroup extends SystemList implements Group {
	
	public SystemGroup(EntryImpl entry, String uri) {
		super(entry, uri);
	}
	
	public SystemGroup(EntryImpl entry, IRI uri) {
		super(entry, uri);
	}

	public void addMember(User user) {
		throw new UnsupportedOperationException();
	}

	public String getName() {
		return ((PrincipalManager) this.entry.getContext()).getPrincipalName(this.getURI());
	}

	public boolean isMember(User user) {
		throw new UnsupportedOperationException();
	}

	public List<URI> memberUris() {
		throw new UnsupportedOperationException();
	}

	public List<User> members() {
		throw new UnsupportedOperationException();
	}

	public boolean removeMember(User user) {
		throw new UnsupportedOperationException();
	}

	public boolean setName(String name) {
		throw new UnsupportedOperationException();
	}

	public Context getHomeContext() {
		throw new UnsupportedOperationException();
	}

	public boolean setHomeContext(Context context) {
		throw new UnsupportedOperationException();
	}

	public Vector<URI> setChildren(Vector<URI> children) {
		setChildren(children, true, true);
		return new Vector<URI>(getChildren()); 
	}

}