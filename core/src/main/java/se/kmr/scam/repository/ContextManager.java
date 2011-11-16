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

package se.kmr.scam.repository;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrdf.repository.RepositoryException;
import org.openrdf.rio.RDFWriter;

/**
 * Manages all non-system Contexts by providing:
 * <nl><li>creation, access, and removal of contexts (methods inherited from Context)</li>
 * <li>Alias management and access to contexts via alias.</li>
 * <li>Retrieval of Entry independent of which Contexts they appear in.</li>
 * <li>Methods for searching metadata (across entry and context boundaries).</li>
 * <li>Adminstration of backups.</li>
 * </nl>
 * All functionality requires that appropriate access control is granted.
 * 
 * @author matthias
 */
public interface ContextManager extends Context{
	
	//***********************************************************************//
	// Alias management and access to contexts via alias.                    //
	//***********************************************************************//
	String getContextAlias(URI contexturi);
	URI getContextURI(String contextAlias);
	boolean setContextAlias(URI contexturi, String newAlias);
	Set<String> getContextAliases();

	//***********************************************************************//
	// Access to contexts via id, see also get(entryId) and getBtMMdUri(uri) //
	//***********************************************************************//

	/**
	 * Same as {@link Context#get(String)} but returns the resource in the entry,
	 * if the entry is not an context, e.g. a folder, null is returned.
	 * 
	 * @param contextId the id for the context in this context (the contextManager).
	 * @return a Context
	 */
	Context getContext(String contextId);
	
	//***********************************************************************//
	// Retrieval of Items independent of which Contexts they appear in.      //
	//***********************************************************************//
	/**
	 * Finds the unique owner entry for a repository URI (i.e. has the same base as the
	 * repositorys base URL). If the URI is a
	 * <nl><li>a MetaMetadata URI - the corresponding entry is returned.</li>
	 * <li>a Metadata URI - the corresponding entry is returned, to access 
	 * potential references to this metadata use the {@link #getItems(URI)}.</li>
	 * <li>a Resource URI - the corresponding owner entry is returned,
	 * to access potential references or links to this resource use 
	 * {@link #getItems(URI)}.</li></nl>
	 * 
	 * @param repositoryURI is a repository URI, it can point to the metametadata, 
	 * the metadata, or to the resource itself.
	 * 
	 * @return an Entry, if null the given URI was not a repository URI. If the 
	 * anyURI corresponds to a repository managed resource the returned entry 
	 * will be {@link LocationType#Local} (guaranteed to be unique).
	 * @throws RepositoryException if something goes wrong, e.g. connection problems 
	 * with the underlying storage or plainly that no item was found for the given URI.
	 * @see #getItems(URI)
	 */
	Entry getEntry(URI repositoryURI);
	
	/**
	 * Finds all references within the repository to the given metadata URI
	 * (that may or may not be a repository URI).
	 * Entry where you lack read-access (to the metadata) are excluded.
	 * 
	 * @param metadataURI is a URI to metadata for a resource. 
	 * @return a set of Entry {@link LocationType#Reference}s to the specified metadata URI.
	 */
	Set<Entry> getReferences(URI metadataURI);

	/**
	 * Finds all links within the repository to the given resource URI.
	 * Entries where you lack read-access (to the metadata) are excluded.
	 * 
	 * @param resourceURI is any URI that you whish to find resources for.
	 * @return a set of Entry {@link LocationType#Link}s to the specified resource.
	 */
	Set<Entry> getLinks(URI resourceURI);

	//***********************************************************************//
	// Methods for searching metadata (across item and context boundaries).  //
	//***********************************************************************//
	
	/**
	 * Search in the repository after entries
	 * 
	 * @param mdQueryString a string which should be a SPARQL query. The query result must
	 * return a URI to an entry. Can be null if you do not want to search metadata information.
	 * <pre>
	 * Example string: 
	 * PREFIX dc:<http://purl.org/dc/terms/> 
	 * SELECT ?namedGraphURI 
	 * WHERE { 
	 * 	GRAPH ?namedGraphURI {
	 * 		?x dc:title ?y
	 * 	} 
	 * }
	 * </pre> 
	 * @param entryQueryString a SPARQL query which searches after entries. Can not be null.
	 * <pre>
	 * Example string: 
	 * PREFIX dc:<http://purl.org/dc/terms/> 
	 * SELECT ?entryURI 
	 * WHERE { 
	 * 		?entryURI dc:created ?y
	 * }
	 * </pre> 
	 * @param list a list with URI:s to contexts, if null all contexts will be returned. 
	 * Remember to take the URI from the "Systems Resource". Should be null if you want to 
	 * search in all contexts.
	 * <pre>
	 * Example: 
	 * List<URI> list = new ArrayList<URI>(); 
	 * list.add(new URI("http://example.se/1/data/{context-id}"))
	 * </pre>  
	 * @return A list with entries which was found or null.
	 * @throws Exception if something goes wrong
	 */
	List<Entry> search(String entryQueryString, String mdQueryString ,List<URI> list) throws Exception; 

	/**
	 * @param predicates
	 *            A list of predicates to indicate which literals should be
	 *            searched.
	 * @param terms
	 *            A list of search terms.
	 * @param lang
	 *            A language for the desired search terms, used for matching
	 *            literals.
	 * @param context
	 *            A list of contexts to look in.
	 * @param andOperation
	 *            Determines whether the search terms should be connected using
	 *            AND or OR.
	 * @return Returns a sorted Map with the highest ranked Entry first. The
	 *         rank is determined by the value which is simply the number of
	 *         occurences of the search terms in the metadata of the matching
	 *         entry.
	 */
	Map<Entry, Integer> searchLiterals(Set<org.openrdf.model.URI> predicates, String[] terms, String lang, List<URI> context, boolean andOperation);
	
	// TODO: not implemented.
	List<Entry> search(String pattern, List<URI> list);
	
	//***********************************************************************//
	// Adminstration of backups.                                             //
	//***********************************************************************//
	List<Date> listBackups(URI contexturi);
	
	String createBackup(URI contexturi) throws RepositoryException;
	
	void restoreBackup(URI contexturi, String fromTime) throws RepositoryException;
	
	String getBackup(URI contexturi, String fromTime);
	
	boolean deleteBackup(URI contexturi, String fromTime);
	
	/**
	 * Exports a context to an RDF file (Trig).
	 * 
	 * @param contextEntry
	 *            The URI of the context to be exported. E.g. http://baseuri/512
	 * @param destFile
	 *            The file to which the RDF should be written.
	 * @param users
	 *            A set which will be filled with a list of user URIs which are
	 *            used in any ACL statement in this context or its entries. This
	 *            parameter is a return value and must not be null. This
	 *            parameter is not used when metadataOnly is true.
	 * @param metadataOnly
	 *            If true only named graphs with local and cached external
	 *            metadata will be exported. This allows for a light-weight
	 *            export of metadata only, to be imported into systems other
	 *            than SCAM.
	 * @throws RepositoryException
	 */
	public void exportContext(Entry contextEntry, File destFile, Set<URI> users, boolean metadataOnly, Class<? extends RDFWriter> writer) throws RepositoryException;
	
	public void importContext(Entry contextEntry, File srcFile) throws RepositoryException, IOException;
	
	/**
	 * Removes all triples in this context (i.e. all triples within all named
	 * graphs which start with the given parameter). Removes even all local
	 * files which belong to this context.
	 * 
	 * @param contextURI
	 *            The URI of the context to be deleted. E.g. http://baseuri/512
	 * @throws RepositoryException
	 */
	public void deleteContext(URI contextURI) throws RepositoryException;
	
}