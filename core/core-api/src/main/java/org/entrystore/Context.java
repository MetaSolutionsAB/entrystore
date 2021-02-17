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

import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.Set;


/**
 * A Context keeps track of a set of {@link Entry}s, at a minimum it provides default
 * ownership according to the access control in it's {@link Entry}.
 *
 * All the methods may throw {@link AuthorizationException} if the user has insufficient
 * rights. If something else goes wrong in a method a {@link org.entrystore.repository.RepositoryException} is
 * thrown instead.
 * 
 * @author matthias
 *
 */
public interface Context extends Resource{
	
	//***********************************************************************//
	// Methods for creating and removing resources, links and references     //
	// in this context.                                                      //
	//***********************************************************************//
	/**
	 * Creates a Entry where the {@link EntryType} is {@link EntryType#Local}.
	 * The creation is allowed if: 
	 * <ul><li>The user has write access to the context. Or</li>
	 * <li>A list is provided in the context where the user has write access.</li></ul>
	 * In the former case the new resources access control will be decided by the 
	 * context, in the latter by the list.
	 * @param entryId, optional value to use for id for the new entry.
	 * @param buiType, which builtin type to use. 
	 * @param repType, which representation type to use. Ignored if buiType is 
	 * anything else than None.
	 * @param listURI the list to add the resource to, or null if the resource
	 * should be created freely in the context.
	 * @return a {@link Entry} containing the resource, it's metadata, 
	 * and it's metametadata. Which exact subclass of resource used depends on the 
	 * {@link GraphType}.
	 * @see {@link GraphType}.
	 */
	Entry createResource(String entryId, GraphType buiType, ResourceType repType, URI listURI); // files, folders, persons

	/**
	 * Creates an Entry where the {@link EntryType} is {@link EntryType#Link}.
	 * The creation is allowed if: 
	 * <ul><li>The user has write access to the context. Or</li>
	 * <li>A list is provided in the context where the user has write access.</li></ul>
	 * In the former case the new link's access control will be decided by the 
	 * context, in the latter by the list.
	 * 
	 * @param entryId, optional value to use for id for the new entry.
	 * @param resourceURI any resource to link to, may be internal or external to the repository.
	 * @param listURI the list to add the link to, or null if the link
	 * should be created freely in the context.
	 * @return a {@link Entry} containing the metadata and metametadata for the link.
	 */
	Entry createLink(String entryId, URI resourceURI, URI listURI); // links to pages, bookmarks

	/**
	 * Creates an Entry where the {@link EntryType} is {@link EntryType#Reference}.
	 * The creation is allowed if: 
	 * <ul><li>The user has write access to the context. Or</li>
	 * <li>A list is provided in the context where the user has write access.</li></ul>
	 * In the former case the new reference's access control will be decided by the 
	 * context, in the latter by the list.
	 * 
	 * @param entryId, optional value to use for id for the new entry.
	 * @param resourceURI any resource to link to, may be internal or external to the repository.
	 * @param metadataURI the resources metadata that we intend to reference.
	 * @param listURI the list to add the reference to, or null if the reference
	 * should be created freely in the context.
	 * @return a {@link Entry} containing metametadata and potentially cached metadata 
	 * for the reference.
	 */
	Entry createReference(String entryId, URI resourceURI, URI metadataURI, URI listURI); // references
	
	Entry createLinkReference(String entryId, URI resourceURI, URI metadataURI, URI listURI); 

	//void move(URI entryUri, URI fromListURI, URI toListURI);
	
	/**
	 * @param entryURI the URI of the resource's metametadata to remove.
	 */
	void remove(URI entryURI);
	
	
	//***********************************************************************//
	// Retrieval methods for the different URIs of the Entry.                 //
	//***********************************************************************//
	/**
	 * @param entryId must be a unique identifier for an item in this context.
	 * @return the Entry with this identifier.
	 */
	Entry get(String entryId);
	
	/**
	 * @param entryURI is the URI to the entry.
	 * @return the Entry that has this entryURI.
	 */
	Entry getByEntryURI(URI entryURI);

	/**
	 * @param metadataURI is the URI to an external Metadata referenced by an item in this context.
	 * @return a set of Entries referencing this metadata, never null.
	 */
	Set<Entry> getByExternalMdURI(URI metadataURI);

	/**
	 * @param resourceURI is the URI to the resource for a set of entries in this context.
	 * @return a set of Entries that has this resourceURI.
	 */
	Set<Entry> getByResourceURI(URI resourceURI);
	
	/**
	 * @return the set of all resources managed in this context
	 * (each resource represented by its URI).
	 */
	Set<URI> getResources();

	/**
	 * @return the set of all entries managed in this context
	 * (each entry represented by its mmd URI).
	 */
	Set<URI> getEntries();
	
	//***********************************************************************//
	// Administration methods, since we do not subclass the MetaMetadata     //
	// class, we have to provide context specific methods here instead.      //
	//***********************************************************************//
	/**
	 * If quota is exceeded new links and references are still allowed.
	 * 
	 * @return The quota in bytes, -1 means unlimited quota. 
	 * The quota limits only the amount of uploaded data, not the amount of metadata 
	 * or metametadata.
	 * 
	 * @see Quota
	 * @see Data
	 */
	long getQuota();
	
	/**
	 * @param quotaInBytes amount of quota in bytes.
	 * @see #getQuota()
	 */
	void setQuota(long quotaInBytes);

	/**
	 * @return Returns a boolean to depict whether the Context's quota defaults
	 *         to the system setting or whether it has been set manually.
	 */
	boolean hasDefaultQuota();

	/**
	 * Removes an eventually set Quota and returns the context's setting to
	 * using the system-wide set default quota.
	 */
	void removeQuota();
	
	/**
	 * @return The use amount of data in bytes (not percentage).
	 */
	long getQuotaFillLevel();
	
	/**
	 * This is a thread-safe method to manage the quota fill level.
	 * 
	 * @param bytes Bytes to add to the quota fill level.
	 */
	public void increaseQuotaFillLevel(long bytes) throws QuotaException;
	
	/**
	 * This is a thread-safe method to manage the quota fill level.
	 * 
	 * @param bytes Bytes to abstract from the quota fill level.
	 */
	public void decreaseQuotaFillLevel(long bytes);
	
	/**
	 * Initializes all the System Entries, i.e. with entryId 
	 * beginning with a '_'.
	 *
	 */
	void initializeSystemEntries();
	
	void reIndex();
	
	public Map<URI, DeletedEntryInfo> getDeletedEntries();
	
	public Map<URI, DeletedEntryInfo> getDeletedEntriesInRange(Date from, Date until);

	/**
	 * A context object may still exist even though the entry is removed from both repository
	 * and cache. Necessary to keep Solr in sync.
	 *
	 * @return True if context is deleted or under deletion, false if not.
	 */
	public boolean isDeleted();

}