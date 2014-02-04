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

package org.entrystore.harvesting.oaipmh.target.crosswalk;

import ORG.oclc.oai.server.crosswalk.Crosswalk;
import ORG.oclc.oai.server.verb.CannotDisseminateFormatException;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import org.entrystore.repository.Data;
import org.entrystore.repository.Entry;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.ResourceType;
import org.entrystore.repository.User;
import org.entrystore.repository.impl.converters.LRE;
import org.entrystore.repository.impl.converters.OERDF2LOMConverter;
import org.entrystore.repository.impl.converters.RDF2LOMConverter;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.repository.util.NS;
import org.ieee.ltsc.datatype.impl.EntityImpl;
import org.ieee.ltsc.lom.LOM;
import org.ieee.ltsc.lom.LOM.Technical.Format;
import org.ieee.ltsc.lom.LOMUtil;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.ieee.ltsc.lom.impl.LOMImpl.MetaMetadata.Contribute;
import org.ietf.mimedir.vcard.VCard;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.URIImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.File;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * Converts SCAM Entry and its metadata graphs to oai_lom.
 * 
 * @author Hannes Ebner
 * @author Eric Johansson
 * @version $Revision$
 */
public class Crosswalk2OaiLom extends Crosswalk {
	
	private static final Logger log = LoggerFactory.getLogger(Crosswalk2OaiLom.class);
	
	Cache lomCache;
	
	boolean cacheDeactivated = false;

	/**
	 * The constructor assigns the schemaLocation associated with this
	 * crosswalk.
	 * 
	 * @param properties
	 *            properties that are needed to configure the crosswalk.
	 */
	public Crosswalk2OaiLom(Properties properties) {
		super("http://ltsc.ieee.org/xsd/LOM http://standards.ieee.org/reading/ieee/downloads/LOM/lomv1.0/xsd/lom.xsd");
	}
	
	private void initCache(RepositoryManager rm) {
		CacheManager cacheMan = rm.getCacheManager();
		if (cacheMan != null) {
			if (!cacheMan.cacheExists("lomxml")) {
				cacheMan.addCache(new Cache("lomxml", 1000, MemoryStoreEvictionPolicy.LRU, true, null, true, 0, 0, true, 1800, null));
				lomCache = cacheMan.getCache("lomxml");
				log.info("Created LOM/XML cache");
			} else {
				lomCache = cacheMan.getCache("lomxml");
			}
		} else {
			cacheDeactivated = true;
		}
	}

	/**
	 * Can this nativeItem be represented in LOM format?
	 * 
	 * @param nativeItem
	 *            a record in native format
	 * @return true if LOM format is possible, false otherwise.
	 */
	public boolean isAvailableFor(Object nativeItem) {
		return nativeItem instanceof Entry;
	}

	/**
	 * Perform the actual crosswalk.
	 * 
	 * @param nativeItem
	 *            the native "item". present in the <metadata> element.
	 * @return a String containing the XML to be stored within the <metadata>
	 *         element.
	 * @exception CannotDisseminateFormatException
	 *                nativeItem doesn't support this format.
	 */
	public String createMetadata(Object nativeItem) throws CannotDisseminateFormatException {
		log.debug("Called createMetadata()");
		
		Entry entry = null;
		if (nativeItem != null && nativeItem instanceof Entry) {
			entry = (Entry) nativeItem;
		} else {
			throw new IllegalArgumentException("Argument must be an entry"); 
		}

		if (!cacheDeactivated && lomCache == null) {
			initCache(entry.getRepositoryManager());
		}
		
		String entryURI = entry.getEntryURI().toString();
		String lomXml = null;
		
		if (lomCache != null) {
			Element cachedE = lomCache.get(entryURI);
			if ((cachedE != null) && (cachedE.getVersion() >= entry.getModifiedDate().getTime())) {
				lomXml = (String) cachedE.getValue();
				log.debug("Found cached LOM/XML of " + entryURI);
			}
		}
		
		if (lomXml == null) {
			lomXml = convertGraphToLOMXML(entry, entry.getMetadataGraph());
			if (lomCache != null && lomXml != null) {
				lomCache.put(new Element(entryURI, lomXml, entry.getModifiedDate().getTime()));
				log.debug("Cached LOM/XML of " + entryURI);
			}
		}

		return lomXml;
	}
	
	private String convertGraphToLOMXML(Entry entry, Graph graph) {
		LOMImpl lom = new LOMImpl();
		ValueFactory vf = graph.getValueFactory();
		String resURIStr = entry.getResourceURI().toString();
		log.info("Converting Graph to LOM with root URI: " + resURIStr);
		URI resURI = vf.createURI(resURIStr);
		URI mdURI = null;
		if (entry.getLocalMetadataURI() != null) {
			mdURI = vf.createURI(entry.getLocalMetadataURI().toString());
		}
//		else if (entry.getExternalMetadataURI() != null) {
//			mdURI = vf.createURI(entry.getExternalMetadataURI().toString());
//		}
		
		RDF2LOMConverter converter = new OERDF2LOMConverter(); // we use the Organic.Edunet converter here
		converter.convertAll(graph, lom, resURI, mdURI);
		addAutomaticValues(entry, lom);
		
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
	
	private void addAutomaticValues(Entry entry, LOMImpl lom) {
		
		// 3.1 Identifier
		lom.newMetaMetadata().newIdentifier(0).newCatalog().setString("URI");
		lom.newMetaMetadata().newIdentifier(0).newEntry().setString(entry.getEntryURI().toString());
		
		// 3.2 Contribute - CREATOR
		if (entry.getCreator() != null) {
			GregorianCalendar creationDate = new GregorianCalendar();
			creationDate.setTime(entry.getCreationDate());
			Contribute contributeCreator = lom.newMetaMetadata().newContribute(-1);
			contributeCreator.newRole().newValue().setString(LOM.MetaMetadata.Contribute.Role.CREATOR);
			contributeCreator.newRole().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
			contributeCreator.newDate().newValue().setDateTime(creationDate);
			contributeCreator.newEntity(-1).setVCard(getVCardFromFOAF(entry.getRepositoryManager(), entry.getCreator()));
		}
		
		// 3.2 Contribute - ENRICHER
		for (java.net.URI contribURI : entry.getContributors()) {
			if (contribURI.toString().endsWith("_admin")) {
				continue;
			}
			Contribute contContributor = lom.newMetaMetadata().newContribute(-1);
			contContributor.newRole().newValue().setString(LRE.MetaMetadata.Contribute.Role.ENRICHER);
			contContributor.newRole().newSource().setString(LRE.LRE_V3P0_VOCABULARY);
			contContributor.newEntity(-1).setVCard(getVCardFromFOAF(entry.getRepositoryManager(), contribURI));
		}
		
		// 3.2 Contribute - VALIDATOR
		for (VCard validatorVCard : getValidatorVCards(entry)) {
			Contribute contValidator = lom.newMetaMetadata().newContribute(-1);
			contValidator.newRole().newValue().setString(LOM.MetaMetadata.Contribute.Role.VALIDATOR);
			contValidator.newRole().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
			contValidator.newEntity(-1).setVCard(validatorVCard);
		}
		
		// 3.3 Metadata Schema
		lom.newMetaMetadata().newMetadataSchema(-1).setString(LOM.LOM_V1P0_VOCABULARY);
		lom.newMetaMetadata().newMetadataSchema(-1).setString(LRE.LRE_V3P0_VOCABULARY);
		
		// 4.1 Format
		String mimeType = entry.getMimetype();
		if (mimeType != null) {
			Set<String> formats = new HashSet<String>();
			for (int i = 0; ; i++) {
				Format format = LOMUtil.getTechnicalFormat(lom, i);
				if (format != null) {
					formats.add(format.string());
				} else {
					break;
				}
			}
			// the format in the metadata replaces eventually existing formats in the entry info
			if (formats.isEmpty()) {
				lom.newTechnical().newFormat(-1).setString(mimeType);
			}
		}

		// 4.2 Size
		long size = -1;
		if (entry.getFileSize() > -1) {
			size = entry.getFileSize();
		} else if (entry.getFileSize() == -1) {
			if (entry.getResourceType().equals(ResourceType.InformationResource) && (entry.getResource() != null)) {
				if (entry.getResource() instanceof Data) {
					File dataFile = ((Data) entry.getResource()).getDataFile();
					if (dataFile != null) {
						size = dataFile.length();
					}
				}
			}
		}
		if (size > -1) {
			lom.newTechnical().newSize().setString(Long.toString(size));
		}
		
		// 4.3 Location is added in RDF2LOMConverter
	}
	
	private Set<VCard> getValidatorVCards(Entry entry) {
		Set<VCard> result = new HashSet<VCard>();
		Graph md = entry.getMetadataGraph();
		Iterator<Statement> annotations = md.match(new URIImpl(entry.getResourceURI().toString()), new URIImpl(NS.lom + "annotation"), null);
		
		while (annotations.hasNext()) {
			Value resource = annotations.next().getObject();
			if (resource instanceof Resource) {
				
				// 8.1 Entity
				
				Iterator<Statement> entityStmnts = md.match((Resource) resource, new URIImpl(NS.lom + "entity"), null);
				if (entityStmnts.hasNext()) {
					Statement entityStmnt = entityStmnts.next();
					Value entity = entityStmnt.getObject();
					if (entity instanceof Resource) {
						VCard vcard = null;
						try {
							vcard = EntityImpl.parseEntity(LOM.Annotation.Entity.TYPE, RDF2LOMConverter.convertGraph2VCardString(md, (Resource) entity), false);
						} catch (ParseException e) {
							log.warn("Unable to convert LOM 8.1 Entity of " + entry.getResourceURI() + " to VCard: " + e.getMessage());
						}
						if (vcard != null) {
							result.add(vcard);
						}
					}
				}
				
			}
		}
		return result;
	}
	
	private org.ietf.mimedir.vcard.VCard getVCardFromFOAF(RepositoryManager rm, java.net.URI principalURI) {
		if (rm == null || principalURI == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		
		Entry principalEntry = rm.getPrincipalManager().getByEntryURI(principalURI);
		String name = EntryUtil.getName(principalEntry);
		String structuredName = EntryUtil.getStructuredName(principalEntry);
		String organization = EntryUtil.getMemberOf(principalEntry);
		String email = EntryUtil.getEmail(principalEntry);
		
		if (name == null) {
			if (principalEntry.getResource() instanceof User) {
				User u = (User) principalEntry.getResource();
				name = u.getName();
				if (name == null) {
					name = principalURI.toString();
				}
			}
		}
		
		org.ietf.mimedir.vcard.VCard result = null;
		String vcardString = RDF2LOMConverter.createVCardString(name, email, organization, structuredName);
		try {
			result = EntityImpl.parseEntity(LOM.MetaMetadata.Contribute.Entity.TYPE, vcardString, false);
		} catch (ParseException e) {
			log.warn(e.getMessage());
		}
		return result;
	}

}