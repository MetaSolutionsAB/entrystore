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

package se.kmr.scam.sqi.query;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.openrdf.model.Graph;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Context;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.impl.converters.RDF2LOMConverter;
import se.kmr.scam.repository.util.QueryResult;
import se.kmr.scam.sqi.result.ResultFormatType;
import se.kmr.scam.sqi.translate.TranslationException;

/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
public class ScamRepositoryQueryManagerImpl extends QueryManagerImpl {
	
	private static Logger log = LoggerFactory.getLogger(ScamRepositoryQueryManagerImpl.class);
	
	/** This object is the central point for accessing a SCAM repository. */
	private RepositoryManagerImpl rm;

	/** Manages all non-system {@link Context}s */
	private ContextManager cm;
	
	private PrincipalManager pm;
	
	/** Time to wait before requesting the RepositoryManager instance */
	private static int sleepTime = 10000;
	
	/** The base URI for the SCAM installation */
	private String scamBaseURI; 
	
	private boolean initialized = false;
	
	Map<String, Long> resultCount = Collections.synchronizedMap(new WeakHashMap<String, Long>());

	protected synchronized void setRepositoryManager(RepositoryManagerImpl rm) {
		if (rm == null) {
			log.warn("setRepositoryManager() received null");
			this.rm = rm;
			initialized = false;
		} else {
			this.rm = rm;
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();
			if (cm != null && pm != null) {
				initialized = true;
			}
		}
	}
	
	public void initialize() {
		ConfigurationManager confManager = null;
		try {
			confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
		} catch (IOException e) {
			log.error("Unable to load SCAM configuration: " + e.getMessage());
			return;
		}
		
		Config config = confManager.getConfiguration();
		
		// Check the URL in the scam.properties file if you get an error here.
		scamBaseURI = config.getString(Settings.SCAM_BASE_URL, "http://scam4.org");
		
		//Setup connection to ScamRepo
		while (!isInitialized()) {
			log.info("Requesting RepositoryManager instance for: " + scamBaseURI);
			rm = RepositoryManagerImpl.getInstance(scamBaseURI);
			if (rm == null) {
				try {
					log.info("RepositoryManager not initialized yet");
					log.info(ScamRepositoryQueryManagerImpl.class.getSimpleName() + " waiting for " + sleepTime + " ms before retrying RepositoryManager");
					Thread.sleep(sleepTime);
				} catch (InterruptedException e) {
					log.warn(e.getMessage());
				}
			} else {
				log.info("Got instance: " + rm);
				setRepositoryManager(rm);
				break;
			}
		}
		
	}
	
	public boolean isInitialized() {
		return initialized;
	}
	
		
	/**
	 * Search in the repository after entries
	 * 
	 * @param query a query of format {@link #getLanguage()}.
	 * @param start start record of the results set. The index of the result set size starts with 1.
 	 * @param resultsSetSize number of results.  0 (zero) = no limit
 	 * @param maxResults maximum number of query results.  0 (zero) = no limit
	 * @param resultsFormat format of the results
	 *
	 * @return A string with hits or null.
	 * @throws Exception if something goes wrong
	 */
	public String query(String query, int start, int resultsSetSize, int maxResults, ResultFormatType resultsFormat) throws TranslationException {
		log.info("query:from= " + getLanguage().toString() 
				+ "\nto= " + QueryLanguageType.LUCENEQL.toString() 
				+ "\nresultsFormat= " + resultsFormat.toString() 
				+ "\nquery= " + query);

		//List<Entry> searchResults = sparqlSearch(query);
		SolrQuery q = new SolrQuery(query);
		q.set("public", "true");
		q.setStart(start);
		q.setRows(resultsSetSize);
		q.addSortField("score", ORDER.desc);
		q.addSortField("modified", ORDER.desc);
		QueryResult r = rm.getSolrSupport().sendQuery(q);
		Set<Entry> searchResults = r.getEntries();
		resultCount.put(query, r.getHits());
		String searchResult = null;
		
		if (resultsFormat == ResultFormatType.LOM) {
			searchResult = createResultsLOM(new ArrayList<Entry>(searchResults), start, resultsSetSize, maxResults);
		} else if (resultsFormat == ResultFormatType.STRICT_LRE) {
			searchResult = createResultsStrictLRE(new ArrayList<Entry>(searchResults), start, resultsSetSize, maxResults);
		} else if (resultsFormat == ResultFormatType.PLRF0) {
			searchResult = createResultsPLRF0(new ArrayList<Entry>(searchResults), start,  resultsSetSize, maxResults);
		}
		
		return searchResult;
	}
	
	/**
	 * count returns the total number for matching metadata records found by query
	 * 
	 * @param query a query of format {@link #getLanguage()}. 
	 * 
	 * @return nr of hits
	 */
	public int count(String query) throws TranslationException {
		log.info("count:query= " + query);
		Long count = null;
		if (resultCount.containsKey(query)) {
			count = resultCount.get(query);
		}
		if (count != null) {
			return count.intValue();
		}

		SolrQuery q = new SolrQuery(query);
		q.set("public", "true");
		QueryResult r = rm.getSolrSupport().sendQuery(q);
		count = r.getHits();
		resultCount.put(query, count);
		
		return count.intValue();
	}
	
	private java.net.URI getCurrentUserAndSetGuestUser() {
		java.net.URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		return currentUserURI;
	}

	private String createResultsLOM(List<Entry> searchResults, int start, int resultsSetSize, int maxResults){
		if (searchResults == null || searchResults.isEmpty()) {
			return null;
		}
		
		int max;
		if (maxResults <= 0 || searchResults.size() <= maxResults ) {
			max =  searchResults.size();
		} else {
			max = maxResults;
		}
		
		if (start > max) {
			return null;
		}
		
		StringBuilder sBuild = new StringBuilder();
	    sBuild.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sBuild.append("<results>");
		
		for (int i = start - 1; i < max && (resultsSetSize <= 0 || i < (start - 1) + resultsSetSize); i++) {
			String lomXML = getLOM(searchResults.get(i));
			sBuild.append(lomXML);
		}
		
		sBuild.append("</results>");
		
		return sBuild.toString();
	}
	
	private String createResultsStrictLRE(List<Entry> searchResults, int start, int resultsSetSize, int maxResults){
		if (searchResults == null || searchResults.isEmpty()) {
			return null;
		}
		int max;
		if (maxResults <= 0 || searchResults.size() <= maxResults ) {
			max =  searchResults.size();
		} else {
			max = maxResults;
		}
		if (start > max) {
			return null;
		}
		
		StringBuilder sBuild = new StringBuilder();
	    
		sBuild.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sBuild.append("<strictLreResults xmlns=\"http://fire.eun.org/xsd/strictLreResults-1.0\" ");
		sBuild.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
		sBuild.append("xsi:schemaLocation=\"http://fire.eun.org/xsd/strictLreResults-1.0 ");
		sBuild.append("http://fire.eun.org/xsd/strictLreResults-1.0.xsd\">");
		
		for (int i = start - 1; i < max && (resultsSetSize <= 0 || i < (start - 1) + resultsSetSize); i++) {
			String lomXML = getLOM(searchResults.get(i));
			sBuild.append(lomXML);
		}
		sBuild.append("</strictLreResults>");		
		
		return sBuild.toString();
	}
	
	private String createResultsPLRF0(List<Entry> searchResults, int start, int resultsSetSize, int maxResults){
		if (searchResults == null || searchResults.isEmpty()) {
			return null;
		}
		int max;
		if (maxResults <= 0 || searchResults.size() <= maxResults ) {
			max =  searchResults.size();
		} else {
			max = maxResults;
		}
		if (start > max) {
			return null;
		}
		StringBuilder sBuild = new StringBuilder();
    
		sBuild.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sBuild.append("<Results xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
		sBuild.append("xsi:schemaLocation=\"");
		sBuild.append("http://www.prolearn-project.org/PLRF/ http://www.cs.kuleuven.be/~stefaan/plql/plql.xsd ");
		sBuild.append("http://ltsc.ieee.org/xsd/LOM http://ltsc.ieee.org/xsd/lomv1.0/lom.xsd\" ");
		sBuild.append("xmlns=\"http://www.prolearn-project.org/PLRF/\">\n");
		sBuild.append("<ResultInfo>\n");
		sBuild.append("<ResultLevel>http://www.prolearn-project.org/PLRF/0</ResultLevel>\n");
		sBuild.append("<QueryMethod>http://www.prolearn-project.org/PLQL/l0</QueryMethod>\n");
		sBuild.append("<Cardinality>").append((new Integer(max)).toString()).append("</Cardinality>\n");
		sBuild.append("</ResultInfo>\n");
		sBuild.append("</Results>\n");
		
		return sBuild.toString();
	}
	
	private String getLOM(Entry entry) {
		String result = "";
		
		if (entry != null) {
			// run as guest
			java.net.URI currentUserURI = getCurrentUserAndSetGuestUser();
			
			try {
				Graph mergedMetadata = entry.getMetadataGraph();
				result = convertGraphToLOMXML(entry, mergedMetadata);
			} finally {
				pm.setAuthenticatedUserURI(currentUserURI);
			}
		}
		return result;
	}
	
	private String convertGraphToLOMXML(Entry entry, Graph graph) {
		LOMImpl lom = new LOMImpl();
		ValueFactory vf = graph.getValueFactory();
		String resURIStr = entry.getResourceURI().toString();
		URI resURI = vf.createURI(resURIStr);
		URI mdURI = null;
		if (entry.getLocalMetadataURI() != null) {
			mdURI = vf.createURI(entry.getLocalMetadataURI().toString());
		}
		
		RDF2LOMConverter converter = new RDF2LOMConverter(); 
		converter.convertAll(graph, lom, resURI, mdURI);
		
		// Convert to an XML String
		JAXBContext jaxbContext;
		StringWriter writer = new StringWriter();
		try {
			jaxbContext = JAXBContext.newInstance("org.ieee.ltsc.lom.jaxb.lomxml");
			final Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, new Boolean(true));
			marshaller.marshal(lom, writer);
		} catch (JAXBException e) {
			log.error("Unable to serialize entry to LOM/XML", e);
		}
		
		return writer.toString();
	}

}