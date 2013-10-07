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

package org.entrystore.harvesting.oaipmh.target.catalog;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.impl.DeletedEntryInfo;
import org.entrystore.repository.util.URISplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ORG.oclc.oai.server.catalog.RecordFactory;

/**
 * @author Hannes Ebner
 */
public class SCAMRecordFactory extends RecordFactory {
	private static final Logger log = LoggerFactory.getLogger(SCAMRecordFactory.class);

	private String repositoryIdentifier = null;

//	private static final String BASE_DATE_FORMAT = SCAMProperties.get("scam.dateFormat");
//	private static final SimpleDateFormat baseDateFormat = new SimpleDateFormat(BASE_DATE_FORMAT);
	
	public static final String DEFAULT_OAI_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

	private SimpleDateFormat oaiDateFormat;

	public SCAMRecordFactory(Properties properties) throws IllegalArgumentException {
		super(properties);
		log.debug("SCAMRecordFactory()");
		
		oaiDateFormat = new SimpleDateFormat(DEFAULT_OAI_DATE_FORMAT);
		oaiDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		
		String className = this.getClass().getSimpleName();
		repositoryIdentifier = properties.getProperty(className + ".repositoryIdentifier");
		if (repositoryIdentifier == null) {
			throw new IllegalArgumentException(className + ".repositoryIdentifier is missing from the properties file");
		}
	}

	/**
	 * Utility method to parse the 'local identifier' from the OAI identifier
	 * 
	 * @param identifier
	 *            OAI identifier (e.g.
	 *            ﻿oai:my.confolio.org:http://my.confolio.org/100/entry/1234)
	 * @return local identifier (e.g. http://my.confolio.org/100/entry/123).
	 */
	public String fromOAIIdentifier(String identifier) {
		log.debug("fromOAIIdentifier()");
		
		String prefix = "oai:" + repositoryIdentifier + ":";
		String entryURI = identifier.substring(prefix.length());
			
		return entryURI;
	}

	/**
	 * Construct an OAI identifier from the native item
	 * 
	 * @param nativeItem
	 *            native Item object
	 * @return OAI identifier
	 */
	public String getOAIIdentifier(Object nativeItem) {
		log.debug("getOAIIdentifier()");
		StringBuffer sb = new StringBuffer();
		sb.append("oai:");
		sb.append(repositoryIdentifier);
		sb.append(":");
		sb.append(getLocalIdentifier(nativeItem));
		return sb.toString();
	}

	/**
	 * Extract the local identifier from the native item
	 * 
	 * @param nativeItem
	 *            native Item object
	 * @return local identifier
	 */
	public String getLocalIdentifier(Object nativeItem) {
		log.debug("getLocalIdentifier()");
		
		if (nativeItem != null) {
			if (nativeItem instanceof Entry) {
				return ((Entry) nativeItem).getEntryURI().toASCIIString();
			} else if (nativeItem instanceof DeletedEntryInfo) {
				return ((DeletedEntryInfo) nativeItem).getEntryURI().toASCIIString(); 
			}
		}
	
		return null;
	}

	/**
	 * get the datestamp from the item
	 * 
	 * @param nativeItem
	 *            a native item presumably containing a datestamp somewhere
	 * @return a String containing the datestamp for the item
	 */
	public String getDatestamp(Object nativeItem) {
		log.debug("getDatestamp()");

		if (nativeItem != null) {
			if (nativeItem instanceof Entry) {
				return oaiDateFormat.format(((Entry) nativeItem).getModifiedDate());
			} else if (nativeItem instanceof DeletedEntryInfo) {
				return oaiDateFormat.format(((DeletedEntryInfo) nativeItem).getDeletionDate());
			}
		}
		
		return null;
	}

	/**
	 * get the setspec from the item
	 * 
	 * @param nativeItem
	 *            a native item presumably containing a setspec somewhere
	 * @return a String containing the setspec for the item
	 * @exception IllegalArgumentException
	 *                Something is wrong with the argument.
	 */
	public Iterator<String> getSetSpecs(Object nativeItem) throws IllegalArgumentException {
		log.debug("getSetSpecs()");

		Entry e = null;
		if (nativeItem instanceof Entry) {
			e = (Entry) nativeItem;
		} else {
			return null;
		}
		
		List<String> setSpecs = new ArrayList<String>();
		String contextURI = e.getContext().getURI().toASCIIString();
		if (SCAMOAICatalog.hasManualSets()) {
			setSpecs.addAll(SCAMOAICatalog.findSetsForContext(contextURI));
		} else {
			setSpecs.add(contextURI);
		}

		if (setSpecs != null) {
			return setSpecs.iterator();
		} else {
			return null;
		}
	}

	/**
	 * Get the about elements from the item
	 * 
	 * @param nativeItem
	 *            a native item presumably containing about information
	 *            somewhere
	 * @return a Iterator of Strings containing &lt;about&gt;s for the item
	 * @exception IllegalArgumentException
	 *                Something is wrong with the argument.
	 */
	public Iterator<String> getAbouts(Object nativeItem) throws IllegalArgumentException {
		log.debug("getAbouts()");
		return null;
	}

	/**
	 * Is the record deleted?
	 * 
	 * @param nativeItem
	 *            a native item presumably containing a possible delete
	 *            indicator
	 * @return true if record is deleted, false if not
	 * @exception IllegalArgumentException
	 *                Something is wrong with the argument.
	 */
	public boolean isDeleted(Object nativeItem) throws IllegalArgumentException {
		log.debug("isDeleted()");
		
		// FIXME for some reason this method is called two times per record (at
		// least with listRecords, strangely this does not happen with
		// listIdentifiers)
		
		if (nativeItem instanceof DeletedEntryInfo) {
			return true;
		} else if (nativeItem instanceof Entry) {
			Entry entry = (Entry) nativeItem;
			RepositoryManager rm = entry.getRepositoryManager();
			PrincipalManager pm = rm.getPrincipalManager();

			// the entry is reported deleted if the guest user does not have access to the metadata
			URI currentUser = pm.getAuthenticatedUserURI();
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			try {
				try {
					pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
				} catch (AuthorizationException ae) {
					return true;
				}
			} finally {
				pm.setAuthenticatedUserURI(currentUser);
			}
			
			// the entry is deleted if it is referenced by the trash list only
			Set<URI> referredBy = entry.getReferringListsInSameContext();
			URI trashURI = URISplit.fabricateURI(rm.getRepositoryURL().toString(), entry.getContext().getEntry().getId(), RepositoryProperties.LIST_PATH, "_trash");
			if ((referredBy.size() == 1) && (referredBy.contains(trashURI))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Allows classes that implement RecordFactory to override the default
	 * create() method. This is useful, for example, if the entire
	 * &lt;record&gt; is already packaged as the native record. Return null if
	 * you want the default handler to create it by calling the methods above
	 * individually.
	 * 
	 * @param nativeItem
	 *            the native record
	 * @param schemaURL
	 *            the schemaURL desired for the response
	 * @param the
	 *            metadataPrefix from the request
	 * @return a String containing the OAI &lt;record&gt; or null if the default
	 *         method should be used.
	 */
	public String quickCreate(Object nativeItem, String schemaLocation, String metadataPrefix) {
		log.debug("quickCreate()");
		// Don't perform quick creates
		return null;
	}

}