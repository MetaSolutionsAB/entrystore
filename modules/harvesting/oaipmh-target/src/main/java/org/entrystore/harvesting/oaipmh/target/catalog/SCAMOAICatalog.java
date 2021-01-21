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

package org.entrystore.harvesting.oaipmh.target.catalog;

import ORG.oclc.oai.server.catalog.AbstractCatalog;
import ORG.oclc.oai.server.verb.BadResumptionTokenException;
import ORG.oclc.oai.server.verb.CannotDisseminateFormatException;
import ORG.oclc.oai.server.verb.IdDoesNotExistException;
import ORG.oclc.oai.server.verb.NoMetadataFormatsException;
import ORG.oclc.oai.server.verb.NoRecordsMatchException;
import ORG.oclc.oai.server.verb.NoSetHierarchyException;
import ORG.oclc.oai.server.verb.OAIInternalServerError;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.DeletedEntryInfo;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;


/**
 *
 * @author Hannes Ebner
 * @author Eric Johansson
 */
public class SCAMOAICatalog extends AbstractCatalog {
	
	/** Logger */
	private static final Logger log = LoggerFactory.getLogger(SCAMOAICatalog.class);

	/** This object is the central point for accessing a SCAM repository. */
	private RepositoryManagerImpl rm;

	/** Manages all non-system {@link Context}s */
	private ContextManager cm;
	
	private PrincipalManager pm;

	/** Maximum number of entries to return for ListRecords and ListIdentifiers */
	private int maxListSize;
	
	/** The base URI of the SCAM installation to be harvested */
	private String scamBaseURI; 

	/** Pending resumption tokens */
	private HashMap resumptionResults = new HashMap();
	
	/** Time to wait before requesting the RepositoryManager instance */
	private static int sleepTime = 10000;
	
	private boolean initialized = false;
	
	private static Config config;
	
	private static boolean automaticSets = true;
	
	private boolean closed = false;
	
	RepositoryManagerGrabber grabber = new RepositoryManagerGrabber();
	
	private class RepositoryManagerGrabber extends Thread {
		
		private Logger log = LoggerFactory.getLogger(RepositoryManagerGrabber.class);
		
		@Override
		public void run() {
			while (!isInitialized() && !closed) {
				log.info("Requesting RepositoryManager instance for: " + scamBaseURI);
				rm = RepositoryManagerImpl.getInstance(scamBaseURI);
				if (rm == null) {
					try {
						log.info("RepositoryManager not initialized yet");
						log.info(SCAMOAICatalog.class.getSimpleName() + " waiting for " + sleepTime + " ms before retrying RepositoryManager");
						Thread.sleep(sleepTime);
					} catch (InterruptedException e) {
						log.warn("Grabber received interrupt: " + e.getMessage());
					}
				} else {
					log.info("Got instance: " + rm);
					setRepositoryManager(rm);
					if (isInitialized()) {
						break;
					}
				}
			}
		}
		
	}

	/**
	 * Construct a SCAMOAICatalog object
	 *
	 * @param properties a properties object containing initialization parameters
	 */
	public SCAMOAICatalog(Properties properties) {
		log.debug("SCAMOAICatalog()");
		
		String className = SCAMOAICatalog.class.getSimpleName();
		String propMaxListSize = properties.getProperty(className + ".maxListSize");
		if (propMaxListSize == null) {
			String noPropError = "Property " + className + ".maxListSize is missing in the OAI-PMH target configuration";
			log.error(noPropError);
			throw new IllegalArgumentException(noPropError);
		}
		maxListSize = Integer.parseInt(propMaxListSize);
		
		scamBaseURI = properties.getProperty(Settings.HARVESTING_TARGET_OAI_BASE_URI);
		if (scamBaseURI == null) {
			String noPropError = "Property " + Settings.HARVESTING_TARGET_OAI_BASE_URI + " is missing in the OAI-PMH target configuration";
			log.error(noPropError);
			throw new IllegalArgumentException(noPropError);
		}
		
		grabber.start();
		
		ConfigurationManager confManager = null;
		try {
			confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI("oai-pmh.properties"));
		} catch (IOException e) {
			log.error("Unable to load SCAM OAI-PMH target configuration: " + e.getMessage());
			return;
		}
		config = confManager.getConfiguration();
		
		automaticSets = config.getBoolean(TargetSettings.OAI_SETS_AUTOMATIC, true);
		log.info("Automatic set definitions: " + automaticSets);

//		PrincipalManager pm = rm.getPrincipalManager();
//		URI currentUserURI = pm.getAuthenticatedUserURI();
//		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
//		/* do stuff */
//		pm.setAuthenticatedUserURI(currentUserURI);
	}
	
	protected synchronized void setRepositoryManager(RepositoryManagerImpl rm) {
		if (rm == null) {
			log.warn("setRepositoryManager() received null");
			rm = null;
			cm = null;
			pm = null;
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
	
	public boolean isInitialized() {
		return initialized;
	}
	
	private void checkForInitialization() throws OAIInternalServerError {
		if (!initialized) {
			log.warn("OAI target has not been initialized yet, aborting request with OAIInternalServerError");
			throw new OAIInternalServerError("OAI target has not been initialized yet, please try back later");
		}
	}
	
	private URI getCurrentUserAndSetGuestUser() {
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		return currentUserURI;
	}
	
	private Date parseOAIDate(String oaiDate) {
		Date date = null;
		
		DateFormat formatter = new SimpleDateFormat(SCAMRecordFactory.DEFAULT_OAI_DATE_FORMAT);
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));

		try {
			date = formatter.parse(oaiDate);
		} catch (ParseException pe) {
			log.error(pe.getMessage());
		}

		return date;
	}
	
	private Set<URI> getContexts() {
		Set<URI> contexts = new HashSet<URI>();
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			Set<String> aliases = cm.getNames();
			for (String contextAlias : aliases) {
				URI contextURI = cm.getContextURI(contextAlias);
				contexts.add(contextURI);
			}
			if (contexts.contains(null)) {
				contexts.remove(null);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		return contexts;
	}

	/**
	 * Retrieve a list of schemaLocation values associated with the specified
	 * identifier.
	 * 
	 * @param identifier
	 *            the OAI identifier
	 * @return a Vector containing schemaLocation Strings
	 * @exception IdDoesNotExistException
	 *                the specified identifier can't be found
	 * @exception NoMetadataFormatsException
	 *                the specified identifier was found but the item is flagged
	 *                as deleted and thus no schemaLocations (i.e.
	 *                metadataFormats) can be produced.
	 */
	public Vector<String> getSchemaLocations(String identifier)	throws IdDoesNotExistException, NoMetadataFormatsException, OAIInternalServerError {
		log.info("getSchemaLocations()"); 
		
		checkForInitialization();
		
		URI currentUserURI = getCurrentUserAndSetGuestUser();
		
		try {
			String entryURI = getRecordFactory().fromOAIIdentifier(identifier);

			String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/") + 1);
			Entry nativeItem = cm.get(entryId); 

			/*
			 * Let your recordFactory decide which schemaLocations
			 * (i.e. metadataFormats) it can produce from the record.
			 * Doing so will preserve the separation of database access
			 * (which happens here) from the record content interpretation
			 * (which is the responsibility of the RecordFactory implementation).
			 */
			if (nativeItem == null) {
				throw new IdDoesNotExistException(identifier);
			} else {
				return getRecordFactory().getSchemaLocations(nativeItem);
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}
	
	protected static List<String> getSetOptions(String setSpec) {
		String key = TargetSettings.OAI_SET_DEFINITION_BASE + setSpec + ".options";
		String options = config.getString(key, "");
		return Arrays.asList(options.split(","));
	}
	
	protected static String getSetDescription(String setSpec) {
		String key = TargetSettings.OAI_SET_DEFINITION_BASE + setSpec + ".description";
		return config.getString(key, "");
	}
	
	protected static Set<String> getSets() {
		return new HashSet<String>(config.getStringList(TargetSettings.OAI_SETS_LIST, new ArrayList<String>()));
	}
	
	protected static Set<URI> getSetContexts(String setSpec) {
		String key = TargetSettings.OAI_SET_DEFINITION_BASE + setSpec + ".contexts";
		List<String> contextList = config.getStringList(key, new ArrayList<String>());
		Set<URI> result = new HashSet<URI>();
		for (String uriStr : contextList) {
			try {
				result.add(new URI(uriStr));
			} catch (URISyntaxException e) {
				log.warn("Invalid URI in definition of set \"" + setSpec + "\": " + uriStr + ". Exception: " + e.getMessage());
				continue;
			}
		}
		return result;
	}
	
	protected static Set<String> findSetsForContext(String contextURI) {
		Set<String> result = new HashSet<String>();
		Set<String> sets = getSets();
		for (String set : sets) {
			Set<URI> setContexts = getSetContexts(set);
			for (URI context : setContexts) {
				if (context.toString().equals(contextURI)) {
					result.add(set);
				}
			}
		}
		return result;
	}
	
	protected static boolean hasManualSets() {
		return !automaticSets;
	}

	/**
	 * Retrieve a list of sets
	 * 
	 * @return a Map object containing "sets" Iterator object (contains
	 *         <setSpec/> XML Strings) as well as an optional resumptionMap Map.
	 * @exception NoSetHierarchyException, OAIInternalServerError
	 *                signals an HTTP status code 400 problem
	 */
	public Map listSets() throws NoSetHierarchyException, OAIInternalServerError {
		log.info("listSets()");
		
		checkForInitialization();

		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Map listSetsMap = new HashMap();
		try {
			purge(); // clean out old resumptionTokens
			List<String> sets = new ArrayList<String>();

			/**********************************************************************
			 * Retrieve list or array of sets, setArray should contains String with following format:
			 *  "<set><setSpec>context</setSpec><setName>All Portfolios</setName></set>",
			 *  "<set><setSpec>context:100</setSpec><setName>Mickes Portfolio</setName></set>",
			 *	"<set><setSpec>context:101</setSpec><setName>Matthias Portfolio</setName></set>"
			 **********************************************************************/
			
			List<String> listSets = new ArrayList<String>();
			
			if (hasManualSets()) {
				Set<String> setSpecs = getSets();
				for (String setSpec : setSpecs) {
					if (setSpec != null && setSpec.length() > 0) {
						String setName = getSetDescription(setSpec);
						String set =
							"<set>" +
							"<setSpec>" + setSpec + "</setSpec>" +
							"<setName>" + setName + "</setName>" +
							"</set>";
						listSets.add(set);
					}
				}
			} else {
				Set<URI> contextURIs = cm.getEntries();
				for (URI contextURI : contextURIs) {
					if (contextURI == null) {
						log.error("ContextManager.getEntries() included a contextURI which was null");
						continue;
					}
					Entry contextEntry = cm.getByEntryURI(contextURI);
					if (contextEntry == null) {
						log.warn("No entry found for " + contextURI);
						continue;
					}
					if (contextEntry.getGraphType().equals(GraphType.Context)) {
						URI resourceURI = contextEntry.getResourceURI();
						String contextAlias = cm.getName(resourceURI);
						if (contextAlias == null) {
							contextAlias = "";
						}
						String set =
							"<set>" +
							"<setSpec>" + resourceURI + "</setSpec>" +
							"<setName>" + contextAlias + "</setName>" +
							"</set>";
						listSets.add(set);
					}
				}
			}

			String[] setsArray = (String[])listSets.toArray(new String[0]);
			/***********************************************************************
			 * END 
			 ***********************************************************************/

			int count; 
			/* load the sets ArrayList */
			for (count=0; count < maxListSize && count < setsArray.length; ++count) {
				sets.add(setsArray[count]);
			}

			/* decide if you're done */
			if (count < setsArray.length) {
				String resumptionId = getResumptionId();

				/*****************************************************************
				 * Store an object appropriate for your database API in the
				 * resumptionResults Map in place of nativeItems. This object
				 * should probably encapsulate the information necessary to
				 * perform the next resumption of ListIdentifiers. It might even
				 * be possible to encode everything you need in the
				 * resumptionToken, in which case you won't need the
				 * resumptionResults Map. Here, I've done a silly combination
				 * of the two. Stateless resumptionTokens have some advantages.
				 *****************************************************************/
				resumptionResults.put(resumptionId, setsArray);

				/*****************************************************************
				 * Construct the resumptionToken String however you see fit.
				 *****************************************************************/
				StringBuffer resumptionTokenSb = new StringBuffer();
				resumptionTokenSb.append(resumptionId);
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(Integer.toString(count));

				/*****************************************************************
				 * Use the following line if you wish to include the optional
				 * resumptionToken attributes in the response. Otherwise, use the
				 * line after it that I've commented out.
				 *****************************************************************/
				listSetsMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(),	setsArray.length, 0));
				// listSetsMap.put("resumptionMap", getResumptionMap(resumptionTokenSbSb.toString()));
			}

			listSetsMap.put("sets", sets.iterator());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}

		return listSetsMap;
	}

	/**
	 * Retrieve the next set of sets associated with the resumptionToken
	 * 
	 * @param resumptionToken
	 *            implementation-dependent format taken from the previous
	 *            listSets() Map result.
	 * @return a Map object containing "sets" Iterator object (contains
	 *         <setSpec/> XML Strings) as well as an optional resumptionMap Map.
	 * @exception BadResumptionTokenException
	 *                the value of the resumptionToken is invalid or expired.
	 */
	public Map listSets(String resumptionToken) throws BadResumptionTokenException, OAIInternalServerError {
		log.info("listSets(" + resumptionToken + ")");

		checkForInitialization();

		Map listSetsMap = new HashMap();
		List<String> sets = new ArrayList<String>();
		purge(); // clean out old resumptionTokens

		/**********************************************************************
		 * parse your resumptionToken and look it up in the resumptionResults,
		 * if necessary
		 **********************************************************************/
		StringTokenizer tokenizer = new StringTokenizer(resumptionToken, ":");
		String resumptionId;
		int oldCount;
		try {
			resumptionId = tokenizer.nextToken();
			oldCount = Integer.parseInt(tokenizer.nextToken());
		} catch (NoSuchElementException e) {
			throw new BadResumptionTokenException();
		}

		/* Get some more sets */
		String[] setsArray = (String[])resumptionResults.remove(resumptionId);
		if (setsArray == null) {
			throw new BadResumptionTokenException();
		}
		int count;

		/* load the sets ArrayList */
		for (count = 0; count < maxListSize && count+oldCount < setsArray.length; ++count) {
			sets.add(setsArray[count+oldCount]);
		}

		/* decide if we're done */
		if (count+oldCount < setsArray.length) {
			resumptionId = getResumptionId();

			/*****************************************************************
			 * Store an object appropriate for your database API in the
			 * resumptionResults Map in place of nativeItems. This object
			 * should probably encapsulate the information necessary to
			 * perform the next resumption of ListIdentifiers. It might even
			 * be possible to encode everything you need in the
			 * resumptionToken, in which case you won't need the
			 * resumptionResults Map. Here, I've done a silly combination
			 * of the two. Stateless resumptionTokens have some advantages.
			 *****************************************************************/
			resumptionResults.put(resumptionId, setsArray);

			/*****************************************************************
			 * Construct the resumptionToken String however you see fit.
			 *****************************************************************/
			StringBuffer resumptionTokenSb = new StringBuffer();
			resumptionTokenSb.append(resumptionId);
			resumptionTokenSb.append(":");
			resumptionTokenSb.append(Integer.toString(oldCount + count));

			/*****************************************************************
			 * Use the following line if you wish to include the optional
			 * resumptionToken attributes in the response. Otherwise, use the
			 * line after it that I've commented out.
			 *****************************************************************/
			listSetsMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(),	setsArray.length, oldCount));
			// listSetsMap.put("resumptionMap",
			// getResumptionMap(resumptionTokenSb.toString()));
		}

		listSetsMap.put("sets", sets.iterator());

		return listSetsMap;
	}

	/**
	 * Retrieve a list of identifiers that satisfy the specified criteria
	 * 
	 * @param from
	 *            beginning date using the proper granularity
	 * @param until
	 *            ending date using the proper granularity
	 * @param set
	 *            the set name or null if no such limit is requested
	 * @param metadataPrefix
	 *            the OAI metadataPrefix or null if no such limit is requested
	 * @return a Map object containing entries for "headers" and "identifiers"
	 *         Iterators (both containing Strings) as well as an optional
	 *         "resumptionMap" Map. It may seem strange for the map to include
	 *         both "headers" and "identifiers" since the identifiers can be
	 *         obtained from the headers. This may be true, but
	 *         AbstractCatalog.listRecords() can operate quicker if it doesn't
	 *         need to parse identifiers from the XML headers itself. Better
	 *         still, do like I do below and override
	 *         AbstractCatalog.listRecords(). AbstractCatalog.listRecords() is
	 *         relatively inefficient because given the list of identifiers, it
	 *         must call getRecord() individually for each as it constructs its
	 *         response. It's much more efficient to construct the entire
	 *         response in one fell swoop by overriding listRecords() as I've
	 *         done here.
	 */
	public Map listIdentifiers(String from, String until, String set, String metadataPrefix) throws OAIInternalServerError, NoRecordsMatchException {
		log.info("listIdentifiers()"); 
		
		checkForInitialization();
		
		URI currentUserURI = getCurrentUserAndSetGuestUser();

		Map listIdentifiersMap = new HashMap();

		try {
			purge(); // clean out old resumptionTokens

			List<String> headers = new ArrayList<String>();
			List<String> identifiers = new ArrayList<String>();

			Date fromDate = null;
			Date untilDate = null;
			
			if (from != null) {
				fromDate = parseOAIDate(from);
			}
			if (until != null) {
				untilDate = parseOAIDate(until);
			}

			/**********************************************************************
			 * Range limits are inclusive: 
			 * 'from' specifies a bound that must be interpreted as "greater than or equal to",
			 * 'until' specifies a bound that must be interpreted as "less than or equal to".
			 * http://www.openarchives.org/OAI/openarchivesprotocol.html#SelectiveHarvestingandDatestamps
			 *
			 * The response must include records that has been created/modified within the bounds of the from and until arguments.
			 * If Delete records is supported, also include resources which have been withdrawn from the repository within the bounds of the from and until arguments
			 * More info about DeleteRecords here:
			 * http://www.openarchives.org/OAI/openarchivesprotocol.html#DeletedRecords
			 * (Modify SCAMRecordFactory.isDeleted() if DeleteRecords should be supported
			 *  and change Identify.deletedRecord=[no|transient|persistent] in resources/oau-pmh.properties)
			 **********************************************************************/

			List<Entry> entries = new ArrayList<Entry>();  
			List<URI> contexts = new ArrayList<URI>();
			Collection<DeletedEntryInfo> deletedEntries = new ArrayList<DeletedEntryInfo>();
			boolean validate = true;
			
			if (hasManualSets()) {
				if (set != null) {
					if (!getSets().contains(set)) {
						throw new NoRecordsMatchException();
					} else {
						contexts.addAll(getSetContexts(set));
						if (getSetOptions(set).contains("unvalidated")) {
							validate = false;
						}
					}
				} else {
					if ("*".equals(config.getString(TargetSettings.OAI_SET_ALL))) {
						contexts.addAll(getContexts());
					} else {
						contexts.addAll(getSetContexts("all"));
					}
					if (getSetOptions("all").contains("unvalidated")) {
						validate = false;
					}
				}
			} else {
				if (set != null) {
					if (set.trim().equalsIgnoreCase("unvalidated")) {
						validate = false;
						contexts.addAll(getContexts());
					} else {
						if (set.trim().startsWith("unvalidated:")) {
							validate = false;
							set = set.substring(set.indexOf(":") + 1);
						}
						URI context;
						try {
							context = new URI(set);
						} catch (URISyntaxException e) {
							log.warn(e.getMessage());
							throw new NoRecordsMatchException();
						}
						contexts.add(context);
					}
				} else {
					contexts.addAll(getContexts());
				}
			}
			
			for (URI uri : contexts) {
				if (uri == null) {
					continue;
				}
				String contextURI = uri.toString();
				String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/") + 1);
				Context context = cm.getContext(contextId);
				Set<URI> contextEntries = context.getEntries();
				for (URI entryURI : contextEntries) {
					try {
						String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/") + 1);
						Entry entry = context.get(entryId);
						if (entry == null) {
							log.warn("No entry found for URI: " + entryURI);
							continue;
						}

						// Check whether we have to validate and whether the resource has been validated
						/*if (validate && !ConverterUtil.isValidated(entry.getMetadataGraph(), entry.getResourceURI())) {
							continue;
						}*/

						Date modificationDate = entry.getModifiedDate();
						boolean inDateRangeFrom = true;
						boolean inDateRangeUntil = true;
						if (modificationDate != null) {
							if (fromDate != null) {
								inDateRangeFrom = modificationDate.compareTo(fromDate) >= 0;
							}
							if (untilDate != null) {
								inDateRangeUntil = modificationDate.compareTo(untilDate) <= 0;
							}
						}

						if (inDateRangeFrom && inDateRangeUntil && entry.getGraphType().equals(GraphType.None)) {
							entries.add(entry);
						}
					} catch (AuthorizationException ae) {
						continue;
					}
				}
				
				Map<URI, DeletedEntryInfo> deletedEntryMap = null;
				try {
					deletedEntryMap = context.getDeletedEntriesInRange(fromDate, untilDate);
				} catch (AuthorizationException ignored) {}

				if (deletedEntryMap != null) {
					deletedEntries.addAll(deletedEntryMap.values());
				}
			}

			// We do it without generics because of 2 different classes here:
			// Entry and DeletedEntryInformation
			List nativeItemList = new ArrayList();
			nativeItemList.addAll(entries);
			nativeItemList.addAll(deletedEntries);

			Object[] nativeItems = nativeItemList.toArray();

			int count;

			/* load the headers and identifiers ArrayLists. */
			for (count=0; count < maxListSize && count < nativeItems.length; ++count) {
				/* Use the RecordFactory to extract header/identifier pairs for each item */
				String[] header = getRecordFactory().createHeader(nativeItems[count]);
				headers.add(header[0]);
				identifiers.add(header[1]);
			}

			/* decide if you're done */
			if (count < nativeItems.length) {
				String resumptionId = getResumptionId();
				/*****************************************************************
				 * Store an object appropriate for your database API in the
				 * resumptionResults Map in place of nativeItems. This object
				 * should probably encapsulate the information necessary to
				 * perform the next resumption of ListIdentifiers. It might even
				 * be possible to encode everything you need in the
				 * resumptionToken, in which case you won't need the
				 * resumptionResults Map. Here, I've done a silly combination
				 * of the two. Stateless resumptionTokens have some advantages.
				 *****************************************************************/
				resumptionResults.put(resumptionId, nativeItems);

				/*****************************************************************
				 * Construct the resumptionToken String however you see fit.
				 *****************************************************************/
				StringBuffer resumptionTokenSb = new StringBuffer();
				resumptionTokenSb.append(resumptionId);
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(Integer.toString(count));
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(metadataPrefix);

				/*****************************************************************
				 * Use the following line if you wish to include the optional
				 * resumptionToken attributes in the response. Otherwise, use the
				 * line after it that I've commented out.
				 *****************************************************************/
				listIdentifiersMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(), nativeItems.length, 0));
				//listIdentifiersMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString()));
			}

			listIdentifiersMap.put("headers", headers.iterator());
			listIdentifiersMap.put("identifiers", identifiers.iterator());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
		
		return listIdentifiersMap;
	}

	/**
	 * Retrieve the next set of identifiers associated with the resumptionToken
	 * 
	 * @param resumptionToken
	 *            implementation-dependent format taken from the previous
	 *            listIdentifiers() Map result.
	 * @return a Map object containing entries for "headers" and "identifiers"
	 *         Iterators (both containing Strings) as well as an optional
	 *         "resumptionMap" Map.
	 * @exception BadResumptionTokenException
	 *                the value of the resumptionToken is invalid or expired.
	 */
	public Map listIdentifiers(String resumptionToken) throws BadResumptionTokenException, OAIInternalServerError {
		log.info("listIdentifiers(" + resumptionToken + ")");
		
		checkForInitialization();

		purge(); // clean out old resumptionTokens
		Map listIdentifiersMap = new HashMap();
		List<String> headers = new ArrayList<String>();
		List<String> identifiers = new ArrayList<String>();

		/**********************************************************************
		 * parse your resumptionToken and look it up in the resumptionResults,
		 * if necessary
		 **********************************************************************/
		StringTokenizer tokenizer = new StringTokenizer(resumptionToken, ":");
		String resumptionId;
		int oldCount;
		String metadataPrefix;
		try {
			resumptionId = tokenizer.nextToken();
			oldCount = Integer.parseInt(tokenizer.nextToken());
			metadataPrefix = tokenizer.nextToken();
		} catch (NoSuchElementException e) {
			throw new BadResumptionTokenException();
		}

		/* Get some more records from your database */
		Object[] nativeItems = (Object[])resumptionResults.remove(resumptionId);
		if (nativeItems == null) {
			throw new BadResumptionTokenException();
		}
		int count;

		/* load the headers and identifiers ArrayLists. */
		for (count = 0; count < maxListSize && count+oldCount < nativeItems.length; ++count) {
			/* Use the RecordFactory to extract header/identifier pairs for each item */
			String[] header = getRecordFactory().createHeader(nativeItems[count+oldCount]);
			headers.add(header[0]);
			identifiers.add(header[1]);
		}

		/* decide if you're done. */
		if (count+oldCount < nativeItems.length) {
			resumptionId = getResumptionId();

			/*****************************************************************
			 * Store an object appropriate for your database API in the
			 * resumptionResults Map in place of nativeItems. This object
			 * should probably encapsulate the information necessary to
			 * perform the next resumption of ListIdentifiers. It might even
			 * be possible to encode everything you need in the
			 * resumptionToken, in which case you won't need the
			 * resumptionResults Map. Here, I've done a silly combination
			 * of the two. Stateless resumptionTokens have some advantages.
			 *****************************************************************/
			resumptionResults.put(resumptionId, nativeItems);

			/*****************************************************************
			 * Construct the resumptionToken String however you see fit.
			 *****************************************************************/
			StringBuffer resumptionTokenSb = new StringBuffer();
			resumptionTokenSb.append(resumptionId);
			resumptionTokenSb.append(":");
			resumptionTokenSb.append(Integer.toString(oldCount + count));
			resumptionTokenSb.append(":");
			resumptionTokenSb.append(metadataPrefix);

			/*****************************************************************
			 * Use the following line if you wish to include the optional
			 * resumptionToken attributes in the response. Otherwise, use the
			 * line after it that I've commented out.
			 *****************************************************************/
			listIdentifiersMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(), nativeItems.length, oldCount));
			//listIdentifiersMap.put("resumptionMap",
			//getResumptionMap(resumptionTokenSb.toString()));
		}

		listIdentifiersMap.put("headers", headers.iterator());
		listIdentifiersMap.put("identifiers", identifiers.iterator());

		return listIdentifiersMap;
	}

	/**
	 * Retrieve the specified metadata for the specified identifier
	 * 
	 * @param identifier
	 *            the OAI identifier
	 * @param metadataPrefix
	 *            the OAI metadataPrefix
	 * @return the <record/> portion of the XML response.
	 * @exception CannotDisseminateFormatException
	 *                the metadataPrefix is not supported by the item.
	 * @exception IdDoesNotExistException
	 *                the identifier wasn't found
	 */
	public String getRecord(String identifier, String metadataPrefix) throws CannotDisseminateFormatException, IdDoesNotExistException, OAIInternalServerError {
		log.info("getRecord()"); 
		
		checkForInitialization();
		
		URI currentUserURI = getCurrentUserAndSetGuestUser();
		
		String record = null;
		try {
			URI entryURI;
			try {
				entryURI = new URI(getRecordFactory().fromOAIIdentifier(identifier));
			} catch (URISyntaxException e) {
				throw new IdDoesNotExistException(e.getMessage());
			}
			
			Entry nativeItem = null;
			try {
				nativeItem = cm.getEntry(entryURI);
			} catch (AuthorizationException ae) {
				log.warn("OAI GetRecord tried to access a resource without being authorized for it");
			}

			if (nativeItem == null) {
				throw new IdDoesNotExistException(identifier);
			}
			
			try {
				record = constructRecord(nativeItem, metadataPrefix);
			} catch (AuthorizationException ae) {}
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
		
		return record;
	}

	/**
	 * Retrieve a list of records that satisfy the specified criteria. Note,
	 * though, that unlike the other OAI verb type methods implemented here,
	 * both of the listRecords methods are already implemented in
	 * AbstractCatalog rather than abstracted. This is because it is possible to
	 * implement ListRecords as a combination of ListIdentifiers and GetRecord
	 * combinations. Nevertheless, I suggest that you override both the
	 * AbstractCatalog.listRecords methods here since it will probably improve
	 * the performance if you create the response in one fell swoop rather than
	 * construct it one GetRecord at a time.
	 * 
	 * @param from
	 *            beginning date using the proper granularity
	 * @param until
	 *            ending date using the proper granularity
	 * @param set
	 *            the set name or null if no such limit is requested
	 * @param metadataPrefix
	 *            the OAI metadataPrefix or null if no such limit is requested
	 * @return a Map object containing entries for a "records" Iterator object
	 *         (containing XML <record/> Strings) and an optional
	 *         "resumptionMap" Map.
	 * @exception CannotDisseminateFormatException
	 *                the metadataPrefix isn't supported by the item.
	 */
	public Map listRecords(String from, String until, String set, String metadataPrefix) throws CannotDisseminateFormatException, OAIInternalServerError, NoRecordsMatchException {
		log.info("listRecords()");

		checkForInitialization();
		
		URI currentUserURI = getCurrentUserAndSetGuestUser();
		Map listRecordsMap = new HashMap();

		try {
			purge(); // clean out old resumptionTokens
			
			Date fromDate = null;
			Date untilDate = null;
			
			if (from != null) {
				fromDate = parseOAIDate(from);
			}
			if (until != null) {
				untilDate = parseOAIDate(until);
			}
			
			List<String> records = new ArrayList<String>();
			List<Entry> entries = new ArrayList<Entry>();
			List<URI> contexts = new ArrayList<URI>();
			Collection<DeletedEntryInfo> deletedEntries = new ArrayList<DeletedEntryInfo>();
			
			boolean validate = true;
			
			if (hasManualSets()) {
				if (set != null) {
					if (!getSets().contains(set)) {
						throw new NoRecordsMatchException();
					} else {
						contexts.addAll(getSetContexts(set));
						if (getSetOptions(set).contains("unvalidated")) {
							validate = false;
						}
					}
				} else {
					if ("*".equals(config.getString(TargetSettings.OAI_SET_ALL))) {
						contexts.addAll(getContexts());
					} else {
						contexts.addAll(getSetContexts("all"));
					}
					if (getSetOptions("all").contains("unvalidated")) {
						validate = false;
					}
				}
			} else {
				if (set != null) {
					if (set.trim().equalsIgnoreCase("unvalidated")) {
						validate = false;
						contexts.addAll(getContexts());
					} else {
						if (set.trim().startsWith("unvalidated:")) {
							validate = false;
							set = set.substring(set.indexOf(":") + 1);
						}
						URI context;
						try {
							context = new URI(set);
						} catch (URISyntaxException e) {
							log.warn(e.getMessage());
							throw new NoRecordsMatchException();
						}
						contexts.add(context);
					}
				} else {
					contexts.addAll(getContexts());
				}
			}
			
			for (URI uri : contexts) {
				if (uri == null) {
					continue;
				}
				
				String contextURI = uri.toString();
				String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/") + 1);
				Context context = cm.getContext(contextId);
				Set<URI> contextEntries = context.getEntries();
				for (URI entryURI : contextEntries) {
					try {
						String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/") + 1);
						Entry entry = context.get(entryId);
						if (entry == null) {
							log.warn("No entry found for URI: " + entryURI);
							continue;
						}
						
						if (entry.getMetadataGraph() == null) {
							log.warn("Entry's metadata graph is null: " + entryURI);
							continue;
						}

						// Check whether we have to validate and whether the resource has been validated
						/*if (validate && !ConverterUtil.isValidated(entry.getMetadataGraph(), entry.getResourceURI())) {
							continue;
						}*/

						Date modificationDate = entry.getModifiedDate();
						boolean inDateRange = true;
						if (modificationDate != null) {
							if (fromDate != null) {
								inDateRange = modificationDate.compareTo(fromDate) >= 0;
							}
							if (untilDate != null) {
								inDateRange = modificationDate.compareTo(untilDate) <= 0;
							}
						}

						if (inDateRange && GraphType.None.equals(entry.getGraphType())) {
							entries.add(entry);
						}
					} catch (AuthorizationException ae) {
						continue;
					}
				}
				
				Map<URI, DeletedEntryInfo> deletedEntryMap = null;
				try {
					deletedEntryMap = context.getDeletedEntriesInRange(fromDate, untilDate);
				} catch (AuthorizationException ignored) {}

				if (deletedEntryMap != null) {
					deletedEntries.addAll(deletedEntryMap.values());
				}
			}

			// We do it without generics because of 2 different classes here:
			// Entry and DeletedEntryInformation
			List nativeItemList = new ArrayList();
			nativeItemList.addAll(entries);
			nativeItemList.addAll(deletedEntries);
			
			Object[] nativeItems = nativeItemList.toArray();
			int count;

			/* load the records ArrayList */
			for (count=0; count < maxListSize && count < nativeItems.length; ++count) {
				String record = constructRecord(nativeItems[count], metadataPrefix);
				records.add(record);
			}

			/* decide if you're done */
			if (count < nativeItems.length) {
				String resumptionId = getResumptionId();

				/*****************************************************************
				 * Store an object appropriate for your database API in the
				 * resumptionResults Map in place of nativeItems. This object
				 * should probably encapsulate the information necessary to
				 * perform the next resumption of ListIdentifiers. It might even
				 * be possible to encode everything you need in the
				 * resumptionToken, in which case you won't need the
				 * resumptionResults Map. Here, I've done a silly combination
				 * of the two. Stateless resumptionTokens have some advantages.
				 *****************************************************************/
				resumptionResults.put(resumptionId, nativeItems);

				/*****************************************************************
				 * Construct the resumptionToken String however you see fit.
				 *****************************************************************/
				StringBuffer resumptionTokenSb = new StringBuffer();
				resumptionTokenSb.append(resumptionId);
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(Integer.toString(count));
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(metadataPrefix);

				/*****************************************************************
				 * Use the following line if you wish to include the optional
				 * resumptionToken attributes in the response. Otherwise, use the
				 * line after it that I've commented out.
				 *****************************************************************/
				listRecordsMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(), nativeItems.length, 0));
				// listRecordsMap.put("resumptionMap",
				// getResumptionMap(resumptionTokenSbSb.toString()));
			}

			listRecordsMap.put("records", records.iterator());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
		
		return listRecordsMap;
	}

	/**
	 * Retrieve the next set of records associated with the resumptionToken
	 * 
	 * @param resumptionToken
	 *            implementation-dependent format taken from the previous
	 *            listRecords() Map result.
	 * @return a Map object containing entries for "headers" and "identifiers"
	 *         Iterators (both containing Strings) as well as an optional
	 *         "resumptionMap" Map.
	 * @exception BadResumptionTokenException
	 *                the value of the resumptionToken argument is invalid or
	 *                expired.
	 */
	public Map listRecords(String resumptionToken) throws BadResumptionTokenException, OAIInternalServerError {
		log.info("listRecords(" + resumptionToken + ")"); 
		
		checkForInitialization();

		purge(); // clean out old resumptionTokens
		Map listRecordsMap = new HashMap();
		URI currentUserURI = getCurrentUserAndSetGuestUser();

		try {
			List<String> records = new ArrayList<String>();

			/**********************************************************************
			 * parse your resumptionToken and look it up in the resumptionResults,
			 * if necessary
			 **********************************************************************/
			StringTokenizer tokenizer = new StringTokenizer(resumptionToken, ":");
			String resumptionId;
			int oldCount;
			String metadataPrefix;
			try {
				resumptionId = tokenizer.nextToken();
				oldCount = Integer.parseInt(tokenizer.nextToken());
				metadataPrefix = tokenizer.nextToken();
			} catch (NoSuchElementException e) {
				throw new BadResumptionTokenException();
			}

			/* Get some more records from your database */
			Object[] nativeItem = (Object[])resumptionResults.remove(resumptionId);
			if (nativeItem == null) {
				throw new BadResumptionTokenException();
			}
			int count;

			/* load the headers and identifiers ArrayLists. */
			for (count = 0; count < maxListSize && count+oldCount < nativeItem.length; ++count) {
				try {
					String record = constructRecord(nativeItem[count+oldCount], metadataPrefix);
					records.add(record);
				} catch (CannotDisseminateFormatException e) {
					/* the client hacked the resumptionToken beyond repair */
					throw new BadResumptionTokenException();
				}
			}

			/* decide if you're done */
			if (count+oldCount < nativeItem.length) {
				resumptionId = getResumptionId();

				/*****************************************************************
				 * Store an object appropriate for your database API in the
				 * resumptionResults Map in place of nativeItems. This object
				 * should probably encapsulate the information necessary to
				 * perform the next resumption of ListIdentifiers. It might even
				 * be possible to encode everything you need in the
				 * resumptionToken, in which case you won't need the
				 * resumptionResults Map. Here, I've done a silly combination
				 * of the two. Stateless resumptionTokens have some advantages.
				 *****************************************************************/
				resumptionResults.put(resumptionId, nativeItem);

				/*****************************************************************
				 * Construct the resumptionToken String however you see fit.
				 *****************************************************************/
				StringBuffer resumptionTokenSb = new StringBuffer();
				resumptionTokenSb.append(resumptionId);
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(Integer.toString(oldCount + count));
				resumptionTokenSb.append(":");
				resumptionTokenSb.append(metadataPrefix);

				/*****************************************************************
				 * Use the following line if you wish to include the optional
				 * resumptionToken attributes in the response. Otherwise, use the
				 * line after it that I've commented out.
				 *****************************************************************/
				listRecordsMap.put("resumptionMap", getResumptionMap(resumptionTokenSb.toString(), nativeItem.length, oldCount));
				// listRecordsMap.put("resumptionMap",
				// getResumptionMap(resumptionTokenSb.toString()));
			}

			listRecordsMap.put("records", records.iterator());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}

		return listRecordsMap;
	}

	/**
	 * Utility method to construct a Record object for a specified
	 * metadataFormat from a native record
	 * 
	 * @param nativeItem
	 *            native item from the dataase
	 * @param metadataPrefix
	 *            the desired metadataPrefix for performing the crosswalk
	 * @return the <record/> String
	 * @exception CannotDisseminateFormatException
	 *                the record is not available for the specified
	 *                metadataPrefix.
	 */
	private String constructRecord(Object nativeItem, String metadataPrefix) throws CannotDisseminateFormatException {
		log.debug("constructRecord()"); 
		String schemaURL = null;

		if (metadataPrefix != null) {
			if ((schemaURL = getCrosswalks().getSchemaURL(metadataPrefix)) == null) {
				throw new CannotDisseminateFormatException(metadataPrefix);
			}
		}
		
		return getRecordFactory().create(nativeItem, schemaURL, metadataPrefix);
	}

	/**
	 * Close the repository.
	 */
	public void close() {
		log.info("Closing OAI-PMH target");
		closed = true;
		setRepositoryManager(null);
	}

	/**
	 * Purge tokens that are older than the configured time-to-live.
	 */
	private void purge() {
		List<String> old = new ArrayList<String>();
		Date now = new Date();
		
		for (Object keyObj : resumptionResults.keySet()) {
			String key = (String) keyObj;
			Date then = new Date(Long.parseLong(key) + getMillisecondsToLive());
			if (now.after(then)) {
				old.add(key);
			}
		}
		
		for (String key : old) {
			resumptionResults.remove(key);	
		}
	}

	/**
	 * Use the current date as the basis for the resumptiontoken
	 * 
	 * @return a String version of the current time
	 */
	private synchronized static String getResumptionId() {
		log.debug("getResumptionId()"); 
		Date now = new Date();
		return Long.toString(now.getTime());
	}

	/**
	 * Extract &lt;set&gt; XML string from setItem object
	 *
	 * @param setItem individual set instance in native format
	 * @return an XML String containing the XML &lt;set&gt; content
	 */
	//    public String getSetXML(HashMap setItem)
	//    throws IllegalArgumentException {
	//        String setSpec = getSetSpec(setItem);
	//        String setName = getSetName(setItem);
	//        String setDescription = getSetDescription(setItem);
	//        
	//        StringBuffer sb = new StringBuffer();
	//        sb.append("<set>");
	//        sb.append("<setSpec>");
	//        sb.append(OAIUtil.xmlEncode(setSpec));
	//        sb.append("</setSpec>");
	//        sb.append("<setName>");
	//        sb.append(OAIUtil.xmlEncode(setName));
	//        sb.append("</setName>");
	//        if (setDescription != null) {
	//            sb.append("<setDescription>");
	//            sb.append(OAIUtil.xmlEncode(setDescription));
	//            sb.append("</setDescription>");
	//        }
	//        sb.append("</set>");
	//        return sb.toString();
	//    }

	/**
	 * get the setSpec XML string. Extend this class and override this method
	 * if the setSpec can't be directly taken from the result set as a String
	 *
	 * @param rs ResultSet
	 * @return an XML String containing the &lt;setSpec&gt; content
	 */
	//    protected String getSetSpec(HashMap setItem) {
	//        try {
	//            return URLEncoder.encode((String)setItem.get(setSpecListLabel), "UTF-8");
	//        } catch (UnsupportedEncodingException e) {
	//            return "UnsupportedEncodingException";
	//        }
	//    }

	/**
	 * get the setName XML string. Extend this class and override this method
	 * if the setName can't be directly taken from the result set as a String
	 *
	 * @param rs ResultSet
	 * @return an XML String containing the &lt;setName&gt; content
	 */
	//    protected String getSetName(HashMap setItem) {
	//        return (String)setItem.get(setNameLabel);
	//    }

	/**
	 * get the setDescription XML string. Extend this class and override this method
	 * if the setDescription can't be directly taken from the result set as a String
	 *
	 * @param rs ResultSet
	 * @return an XML String containing the &lt;setDescription&gt; content
	 */
	//    protected String getSetDescription(HashMap setItem) {
	//        if (setDescriptionLabel == null)
	//            return null;
	//        return (String)setItem.get(setDescriptionLabel);
	//    }
	//

}