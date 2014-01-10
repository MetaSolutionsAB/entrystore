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

package org.entrystore.repository.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.entrystore.repository.ResourceType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.LocationType;
import org.entrystore.repository.Metadata;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.impl.converters.NS;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class automatically validates metadata by inserting triples into the
 * metadata graph. Validation is only performed on entries which are references,
 * conversion from reference to linkreference is done before the local metadata
 * graph is set.
 * 
 * Validation numbers can be checked via the statistics resource, e.g.:
 * http://localhost:8181/5/statistics/properties?labels=lom&profile=organicedunet
 * 
 * @author Hannes Ebner
 */
public class OEAutomaticValidation {
	
	private static Logger log = LoggerFactory.getLogger(OEAutomaticValidation.class);
	
	private PrincipalManager pm;
	
	private ContextManager cm;
	
//	private RepositoryManager rm;
	
//	private Writer writer;
	
	public OEAutomaticValidation(RepositoryManager rm) {
		this.pm = rm.getPrincipalManager();
		this.cm = rm.getContextManager();
//		this.rm = rm;
//		try {
//			writer = new PrintWriter("/tmp/fix.log");
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}
	
	private static void log(String msg) {
		log.info(msg);
//		try {
//			writer.write(msg + "\n");
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}
	
	private List<Entry> getEntries(Set<URI> contexts) {
		List<Entry> entries = new ArrayList<Entry>();
		for (URI uri : contexts) {
			if (uri == null) {
				continue;
			}
			String contextURI = uri.toString();
			String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/") + 1);
			Context context = cm.getContext(contextId);
			Set<URI> contextEntries = context.getEntries();
			for (URI entryURI : contextEntries) {
				String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/") + 1);
				Entry entry = context.get(entryId);
				if (entry == null) {
					log.warn("No entry found for URI: " + entryURI);
					continue;
				}
				entries.add(entry);
			}
		}
		return entries;
	}	
	
	private void validateMetadataOfEntry(Entry entry) {
		if (LocationType.Reference.equals(entry.getLocationType())) {
			entry.setLocationType(LocationType.LinkReference);
		} else {
			log.info("Entry is not of location type reference, skipping");
			return;
		}
		
		Metadata localMd = entry.getLocalMetadata();
		if (localMd == null || localMd.getGraph() == null) {
			log.error("No metadata found for entry, skipping");
			return;
		}
		
		Graph metadata = entry.getLocalMetadata().getGraph();
		ValueFactory vf = metadata.getValueFactory();
		URI resURI = entry.getResourceURI();
		if (resURI == null) {
			log.error("Resource URI is null!");
			return;
		}
		
		org.openrdf.model.URI resourceURI = vf.createURI(resURI.toString());
		
		// Validation information
		BNode bnodeAnn = vf.createBNode();
		BNode bnodeEnt = vf.createBNode();
		BNode bnodeOrg = vf.createBNode();
		metadata.add(resourceURI, vf.createURI(NS.lom, "annotation"), bnodeAnn);
		metadata.add(bnodeAnn, vf.createURI("http://organic-edunet.eu/LOM/rdf/validationStatus"), vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#Accepted"));
		metadata.add(bnodeAnn, vf.createURI(NS.dcterms, "date"), vf.createLiteral(DataCorrection.createW3CDTF(new Date()), vf.createURI("http://purl.org/dc/terms/W3CDTF")));
		metadata.add(bnodeAnn, vf.createURI(NS.lom, "entity"), bnodeEnt);
		metadata.add(bnodeEnt, vf.createURI(NS.vcard, "FN"), vf.createLiteral("Lynda Gibbins"));
		metadata.add(bnodeEnt, vf.createURI(NS.vcard, "EMAIL"), vf.createLiteral("lynda.gibbins@nottingham.ac.uk"));
		metadata.add(bnodeEnt, vf.createURI(NS.vcard, "ORG"), bnodeOrg);
		metadata.add(bnodeOrg, vf.createURI(NS.vcard, "Orgname"), vf.createURI("http://www.intute.ac.uk"));
		
		// Copyright information
		BNode bnodeRights = vf.createBNode();
		metadata.add(resourceURI, vf.createURI(NS.lom, "copyrightAndOtherRestrictions"), vf.createLiteral(true)); 
		metadata.add(resourceURI, vf.createURI(NS.dcterms, "rights"), bnodeRights);
		metadata.add(bnodeRights, RDF.VALUE, vf.createLiteral(
				"The resource or at least part of it can be openly accessed, but specific " +
				"copyright conditions may apply for its use/re-use. It is advisable to consult " +
				"any specific copyright clauses included at the resource location, since Intute " +
				"holds no responsibility for any violation of stated restrictions in accessing/" +
				"using the resource.", "en"));
		
		// Language information
		BNode bnodeLang = vf.createBNode();
		metadata.add(resourceURI, vf.createURI(NS.dcterms, "language"), bnodeLang);
		metadata.add(bnodeLang, RDF.TYPE, vf.createURI(NS.dcterms, "LinguisticSystem"));
		metadata.add(bnodeLang, RDF.VALUE, vf.createLiteral("en", vf.createURI("http://purl.org/dc/terms/RFC4646")));

		localMd.setGraph(metadata);
		
		log.info("Validated metadata of " + entry.getEntryURI());
	}
	
	public void validateMetadata(URI context, URI listEntryURI) {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			Set<URI> contexts = new HashSet<URI>();
			contexts.add(context);
			List<Entry> entries = getEntries(contexts);
			Entry list = cm.getByEntryURI(listEntryURI);
			if (!ResourceType.List.equals(list.getResourceType())) {
				log.error("List parameter is not a list, interrupting");
				return;
			}
			org.entrystore.repository.List l = (org.entrystore.repository.List) list.getResource();
			for (Entry entry : entries) {
				if (LocationType.Reference.equals(entry.getLocationType()) && ResourceType.None.equals(entry.getResourceType())) {
					validateMetadataOfEntry(entry);
					l.addChild(entry.getEntryURI());
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
//		try {
//			writer.flush();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}

}