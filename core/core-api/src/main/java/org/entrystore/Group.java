/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

public interface Group extends Resource {

	boolean setChildren(java.util.List<URI> children);

	/**
	 * Returns the raw RDF-graph of the group's member list.
	 *
	 * @return An RDF-graph.
	 */
	Model getGraph();

	/**
	 * Sets group members using a raw RDF-graph. The graph should use {@code rdf:Seq}
	 * with {@code rdf:_N} predicates pointing to User entry URIs. All referenced entries
	 * must exist in the same context. Duplicates are rejected.
	 *
	 * @param graph RDF-graph containing statements with group members.
	 * @throws IllegalArgumentException if graph is null.
	 */
	void setGraph(Model graph);

	public String getName();

	public boolean setName(String name);

	Context getHomeContext();

	boolean setHomeContext(Context context);

	public void addMember(User user);

	public boolean removeMember(User user);

	public boolean isMember(User user);

	public java.util.List<User> members();

	public java.util.List<URI> memberUris();

}