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

package org.entrystore;

import org.eclipse.rdf4j.model.Model;

import java.io.IOException;
import java.net.URI;

/**
 * List contains a ordered list of resources/links/references in a context.
 * The list itself is very lightwheight, consitited only by an ordered 
 * list of metametadata URIs.
 * 
 * A lists access control overrides the context where it appears. Hence,
 * a user can be given limited access, e.g. creating new resources by having 
 * write access on a list in a context where he/she has no rights.
 * 
 * @author matthias
 */
public interface List extends Resource {
	
	java.util.List<URI> getChildren();
	
	/**
	 * Sets the list of children to the list given. Will fail if any of the children is not
	 * a entry in the current context or if the old children was not allowed to be removed, 
	 * see the {@link #removeChild(URI)} method.
	 * @param children the new list of children, given by the entryURI.
	 * @return false it fails to set the new list of children or remove the old.
	 */
	boolean setChildren(java.util.List<URI> children);
	
	/**
	 * Adds an already existing (in this context) resource/link/reference to 
	 * the list. To create a new resource (in this context) and add it to this
	 * list, use one of the create methods in the {@link Context} and provide 
	 * the URI of this List as argument (List URI, not the List's metadata 
	 * or metametadata URI).
	 * 
	 * @param child the URI of the metametadata of the resource/ to add.
	 */
	void addChild(URI child);
	
	/**
	 * Moves an entry to this list and removes it from another.
	 * 
	 * @param entry to move can be an entire folder tree.
	 * @param fromList a list where from the entry will be removed.
	 * @param removeAll if true the entry(or entries in case of a folder tree)
	 * will be removed from all lists in the originating context, not only fromList.
	 * @throws IOException 
	 * @throws QuotaException 
	 */
	Entry moveEntryHere(URI entry, URI fromList, boolean removeAll) throws QuotaException, IOException;
	
	/**
	 * Moves one child after another.
	 * 
	 * @param child the child to move specified via it's metametadata URI.
	 * @param afterChild the child whereto the other child will be moved
	 * after, specified via it's metametadata URI.
	 */
	void moveChildAfter(URI child, URI afterChild);

	/**
	 * Moves one child before another.
	 * 
	 * @param child the child to move specified via it's metametadata URI.
	 * @param beforeChild the child whereto the other child will be moved
	 * before, specified via it's metametadata URI.
	 */
	void moveChildBefore(URI child, URI beforeChild);

	/**
	 * Removes a child from the current list, but not from the context. 
	 * This method will fail for users with access only on the list but not 
	 * on the surrounding context iff the result would be an orphaned resource.
	 * Orphaned resource means a resource that does not appear in any lists.
	 * If this method fails, the user can try to remove the child completely
	 * by calling the {@link Context#remove(URI)}, this will succeed if the
	 * user has write access on the child itself.
	 * 
	 * @param child the child to remove specified via it's metametadata URI.
	 * @return false if remove failed due to orphaning not allowed for users.
	 */
	boolean removeChild(URI child);
	
	/**
	 * Removes all entries (and lists) that appears in the tree formed 
	 * by this list it's sublists, it's subsublists etc.
	 * Entrys that only appear in the tree will be removed completely, that is,
	 * they will not be orphaned. Hence, entries that also occurs in a list that
	 * is not part of the tree is only removed from the tree, not removed completely.
	 * 
	 * For simplicity of implementation this method requires that the caller is an
	 * owner of the current context.
	 */
	void removeTree();

	/**
	 * Applies the ACL of the current list to its children.
	 * 
	 * @param recursive
	 *            Determines whether all children should be covered. If false,
	 *            only the ACL of the first level of children is changed.
	 */
	void applyACLtoChildren(boolean recursive);

	/**
	 * Returns the raw RDF-graph of the list.
	 *
	 * @return An RDF-graph.
	 */
	Model getGraph();

	/**
	 * Sets list members using a raw RDF-graph.
	 *
	 * @param graph RDF-graph containing statements with list members.
	 */
	void setGraph(Model graph);
	
}