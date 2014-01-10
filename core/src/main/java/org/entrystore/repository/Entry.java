/**
 * Copyright (c) 2007-2010
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

package org.entrystore.repository;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;




/**
 * An Entry in a Context keeps track of information such as date of creation/modification
 * who was the initial contributor, who has contributed, who has access (read/write) etc.
 * It also maintains the EntryType, the RepresentationType and the ResourceType.
 * All this information is represented as an RDF graph as well as through separate methods.
 * Hence, if you need to extend the amount of information stored on the entry level you
 * can use the RDF graph for this.
 * 
 * Depending on the EntryType it also maintains references to a metadata and a resource object.
 * If the EntryType is Reference there might be a cached metadata object as well.
 * 
 * @author matthias
 */
public interface Entry {
	
	/**
	 * @return identifier for an entry which is unique within the current context and repository.
	 */
	String getId();
	
	/**
	 * @return URI for the entry, distinct from metadata and resource URIs.
	 */
	URI getEntryURI();
	
	/**
	 * @return a uri for the resource.
	 */
	URI getResourceURI();
	
	/**
	 * @return a URI for the relation.
	 */
	URI getRelationURI();
	
	
	/**
	 * @return {@link Resource} object, null if the {@link EntryType} is
	 * not {@link EntryType#Local}.
	 */
	Resource getResource();

	/**
	 * returns different things depending on the {@link EntryType}:
	 * <ul><li>LocalMetadata if {@link EntryType#Local} or {@link EntryType#Link}</li>
	 * <li>ExternalMetadata if {@link EntryType#Reference}</li>
	 * <li>A merge between LocalMetadata and ExternalMetadata if {@link EntryType#LinkReference}.</li></ul>
	 * 
	 * The above requires that the ExternalMetadata is cahced locally, if not an empty graph is returned.
	 * 
	 * 
	 * @return {@link Graph}, never null.
	 */
	Graph getMetadataGraph();

	/**
	 * @return the URI for retrieving the metadata from the repository, if the {@link EntryType}
	 * is {@link EntryType#Reference} it is the URI to the cached metadata within the repository
	 * that is returned.
	 * @see 
	 */
	URI getLocalMetadataURI();
	
	/**
	 * @return {@link Metadata} object, null if the {@link EntryType} is
	 * {@link EntryType#Reference} and there is no cache
	 * (check {@link Entry#getExternalMetadataCacheDate()}).
	 */
	Metadata getLocalMetadata();
	
	/**
	 * @return an URI to the original source of the metadata, different from {@link #getLocalMetadataURI()} only
	 * when the {@link EntryType} is {@link EntryType#Reference}.
	 */
	URI getExternalMetadataURI();
	
	/**
	 * @return an URI to the cached Metadata object of the external Metadata.
	 */
	URI getCachedExternalMetadataURI();
	
	/**
	 * The Metadata object is only relevant if {@link #isExternalMetadataCached()} returns true.
	 * @return {@link Metadata} object, never null.
	 */
	Metadata getCachedExternalMetadata();

	/** 
	 * @return false if the externalMetadata is not cached yet or if the 
	 * {@link EntryType} is {@link EntryType#Local} or {@link EntryType#Link}.
	 * @see Entry#getExternalMetadataCacheDate().
	 */
	boolean isExternalMetadataCached();
	
	/**
	 * @return the Date when the external metadata was cached, null if not cached yet or if
	 * {@link EntryType} is not {@link EntryType#Reference} or {@link EntryType#LinkReference}.
	 */
	Date getExternalMetadataCacheDate();
	
	/**
	 * @return a RDF graph (Sesame {@link Graph}) containing the entry information.
	 */
	Graph getGraph();
	
	// TODO need comments
	List<Statement> getRelations(); 
	
	/**
	 * @param entryInfo the new graph, should not violate any restriction
	 * described in any of the set methods in this interface.
	 */
	void setGraph(Graph entryInfo);
	
	/**
	 * The list of lists is updated automatically when the resource is 
	 * added or removed from a lists.
	 * @return a java.util.List of URIs for the lists in this context where this resource occurs.
	 */
	Set<URI> getReferringListsInSameContext();
	
	/**
	 * @param prop corresponds to which {@link AccessProperty} to 
	 * find allowed principals for.
	 * @return a set of principals that are allowed according to the 
	 * {@link AccessProperty} prop.
	 */
	Set<URI> getAllowedPrincipalsFor(AccessProperty prop);
	
	/**
	 * @return true if any access control is expressed on this entry explicitly.
	 */
	boolean hasAllowedPrincipals();
	
	/**
	 * @param prop corresponds to which {@link AccessProperty} to allow for.
	 * @param principals a set of principals that are allowed according to the 
	 * {@link AccessProperty} prop.
	 */
	void setAllowedPrincipalsFor(AccessProperty prop, Set<URI> principals);
	
	/**
	 * @param prop corresponds to which {@link AccessProperty} to allow for.
	 * @param principal a URI of principal that are to be allowed according to the 
	 * {@link AccessProperty} prop.
	 */
	void addAllowedPrincipalsFor(AccessProperty prop, URI principal);

	/**
	 * @param prop corresponds to which {@link AccessProperty} allowed for.
	 * @param principal a URI of principal that are not to be allowed according to the 
	 * {@link AccessProperty} prop.
	 */
	boolean removeAllowedPrincipalsFor(AccessProperty prop, URI principal);

	/**
	 * @return {@link Context} object, never null.
	 */
	Context getContext();
	
	/**
	 * @return the singleton RepositoryManager, never null.
	 */
	RepositoryManager getRepositoryManager();
	
	
	//***************Utility methods************************************//
	// The functionality below for accessing and updating information   //
	// is not strictly neccessary as it can be done indirectly via the  //
	// get and set methods for the entryinfo graph.                     //
	// It is exposed here to simplify for other layers.                 //
	//******************************************************************//
	/**
	 * @return the Date when this entry was created.
	 */
	Date getCreationDate();

	/**
	 * @return Returns the creator as a user URI.
	 */
	URI getCreator();
	
	/**
	 * @return Returns the contributors as a set of URIs.
	 */
	Set<URI> getContributors();

	/**
	 * @return the Date when the entry, i.e. entryinfo, metadata or resource was last modified.
	 */
	Date getModifiedDate();
	
	/**
	 * @return the RepresentationType of the resource.
	 */
	RepresentationType getRepresentationType();

	/**
	 * If the {@link ResourceType} is {@link ResourceType#None} any value on
	 * {@link RepresentationType} is allowed, otherwise only
	 * {@link RepresentationType#True} is allowed.
	 * 
	 * @param ir the new information resource status 
	 */
	void setRepresentationType(RepresentationType ir);

	/**
	 * @return the {@link ResourceType} of the resource, independent of wether
	 * it is managed by the repository, a link or a reference.
	 */
	ResourceType getResourceType();
	
	/**
	 * Changing the builtin type is only allowed for links and references and 
	 * should only be done to better reflect the true nature of the remote 
	 * resource. The change should preferrably be done as a result of some 
	 * automatic detection scheme rather than letting end users manually configure it.
	 * 
	 * @param bt the new builtin type.
	 */
	void setResourceType(ResourceType bt);
	
	/**
	 * @return the {@link EntryType}
	 */
	EntryType getLocationType();

	/**
	 * Sets the {@link EntryType}.
	 */
	void setLocationType(EntryType entryType);

	String getFilename();
	
	void setFilename(String name);

	long getFileSize();
	
	void setFileSize(long size);

	String getMimetype();
	
	void setMimetype(String mt);

	void setResourceURI(URI resourceURI);

	void setExternalMetadataURI(URI externalMetadataURI);	
}