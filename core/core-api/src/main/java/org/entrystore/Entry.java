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

import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.Statement;
import org.entrystore.repository.RepositoryManager;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Set;




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
 * @author Matthias Palmér
 * @author Hannes Ebner
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
	 * @param prop corresponds to which {@link org.entrystore.PrincipalManager.AccessProperty} to
	 * find allowed principals for.
	 * @return a set of principals (entry URIs, not resource URIs) that are allowed according to the
	 * {@link org.entrystore.PrincipalManager.AccessProperty} prop.
	 */
	Set<URI> getAllowedPrincipalsFor(PrincipalManager.AccessProperty prop);
	
	/**
	 * @return true if any access control is expressed on this entry explicitly.
	 */
	boolean hasAllowedPrincipals();
	
	/**
	 * @param prop corresponds to which {@link org.entrystore.PrincipalManager.AccessProperty} to allow for.
	 * @param principals a set of principals that are allowed according to the 
	 * {@link org.entrystore.PrincipalManager.AccessProperty} prop. Note, must be resource URIs, not EntryURIs.
	 */
	void setAllowedPrincipalsFor(PrincipalManager.AccessProperty prop, Set<URI> principals);
	
	/**
	 * @param prop corresponds to which {@link org.entrystore.PrincipalManager.AccessProperty} to allow for.
	 * @param principal a URI of principal that are to be allowed according to the 
	 * {@link org.entrystore.PrincipalManager.AccessProperty} prop.
	 */
	void addAllowedPrincipalsFor(PrincipalManager.AccessProperty prop, URI principal);

	/**
	 * @param prop corresponds to which {@link org.entrystore.PrincipalManager.AccessProperty} allowed for.
	 * @param principal a URI of principal that are not to be allowed according to the 
	 * {@link org.entrystore.PrincipalManager.AccessProperty} prop.
	 */
	boolean removeAllowedPrincipalsFor(PrincipalManager.AccessProperty prop, URI principal);

	/**
	 * @return {@link Context} object, never null.
	 */
	Context getContext();
	
	/**
	 * @return the singleton RepositoryManager, never null.
	 */
	RepositoryManager getRepositoryManager();

	/**
	 * The provencance for this entry. Null if not enabled for this entry.
	 *
	 * @return a provenance instance for this entry, may be null.
	 */
	Provenance getProvenance();

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
	 * Sets the creator of the entry.
	 *
	 * Can only be used by the administrator of the entry.
	 *
	 * @param userURI The URI of the user to be set as creator.
	 */
	void setCreator(URI userURI);
	
	/**
	 * @return Returns the contributors as a set of URIs.
	 */
	Set<URI> getContributors();

	/**
	 * @return the Date when the entry, i.e. entryinfo, metadata or resource was last modified.
	 */
	Date getModifiedDate();
	
	/**
	 * @return the ResourceType of the resource.
	 */
	ResourceType getResourceType();

	/**
	 * If the {@link GraphType} is {@link GraphType#None} any value on
	 * {@link ResourceType} is allowed, otherwise only
	 * {@link ResourceType#True} is allowed.
	 * 
	 * @param rt the new ResourceType
	 */
	void setResourceType(ResourceType rt);

	/**
	 * @return the {@link GraphType} of the resource, independent of whether
	 * it is managed by the repository, a link or a reference.
	 */
	GraphType getGraphType();
	
	/**
	 * Changing the GraphType is only allowed for links and references and 
	 * should only be done to better reflect the true nature of the remote 
	 * resource. The change should preferably be done as a result of some 
	 * automatic detection scheme rather than letting end users manually configure it.
	 * 
	 * @param gt the new GraphType.
	 */
	void setGraphType(GraphType gt);
	
	/**
	 * @return the {@link EntryType}
	 */
	EntryType getEntryType();

	/**
	 * Sets the {@link EntryType}.
	 */
	void setEntryType(EntryType entryType);

	String getFilename();
	
	void setFilename(String name);

	long getFileSize();
	
	void setFileSize(long size);

	String getMimetype();
	
	void setMimetype(String mt);

	void setResourceURI(URI resourceURI);

	void setExternalMetadataURI(URI externalMetadataURI);

	URI getStatus();

	void setStatus(URI newStatus);

	/**
	 * An entry object may still exist even though the entry is removed from both repository
	 * and cache. To avoid processing stale entries this method can be used.
	 *
	 * @return True if entry is deleted, false otherwise.
	 */
	boolean isDeleted();

}