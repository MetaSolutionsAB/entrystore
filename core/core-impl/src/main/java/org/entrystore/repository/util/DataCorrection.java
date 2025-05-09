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

package org.entrystore.repository.util;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.eclipse.rdf4j.model.util.Values.getValueFactory;
import static org.eclipse.rdf4j.model.util.Values.iri;


/**
 * This is a utility class for various context/triple manipulations to correct or migrate data.
 * 
 * @author Hannes Ebner
 */
public class DataCorrection {
	
	private static final Logger log = LoggerFactory.getLogger(DataCorrection.class);
	
	private PrincipalManager pm;
	
	private ContextManager cm;
	
	public DataCorrection(RepositoryManager rm) {
		this.pm = rm.getPrincipalManager();
		this.cm = rm.getContextManager();
	}

	public static String createW3CDTF(java.util.Date date) {
		SimpleDateFormat W3CDTF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		return W3CDTF.format(date);
	}
	
	private Set<URI> getContexts() {
		Set<URI> contexts = new HashSet<URI>();
		Set<String> aliases = cm.getNames();
		for (String contextAlias : aliases) {
			URI contextURI = cm.getContextURI(contextAlias);
			contexts.add(contextURI);
		}
		if (contexts.contains(null)) {
			contexts.remove(null);
		}

		return contexts;
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
	
	private void fixMetadataOfPrincipal(Entry entry) {
		if (entry == null) {
			return;
		}
		Model metadata = entry.getGraph();
		if (metadata != null) {
			IRI resourceURI = iri(entry.getResourceURI().toString());
			IRI metadataURI = iri(entry.getLocalMetadata().getURI().toString());

			Statement resourceRights = getValueFactory().createStatement(resourceURI, iri(NS.entrystore, "write"), resourceURI);
			Statement metadataRights = getValueFactory().createStatement(metadataURI, iri(NS.entrystore, "write"), resourceURI);
			
			if (!metadata.filter(resourceRights.getSubject(), resourceRights.getPredicate(), resourceRights.getObject()).iterator().hasNext()) {
				metadata.add(resourceRights);
				log.info("Added statement: " + resourceRights);
			}
			if (!metadata.filter(metadataRights.getSubject(), metadataRights.getPredicate(), metadataRights.getObject()).iterator().hasNext()) {
				metadata.add(metadataRights);
				log.info("Added statement: " + metadataRights);
			}
			
			entry.setGraph(metadata);
		}
	}
	
	private void fixMetadataOfEntry(Entry entry) {
		Metadata localMd = entry.getLocalMetadata();
		if (localMd != null) {
			Model metadata = entry.getLocalMetadata().getGraph();
			if (metadata != null) {
				boolean updateNecessary = false;
				URI rURI = entry.getResourceURI();
				if (rURI == null) {
					log.error("Resource URI is null!");
					return;
				}
				IRI resURI = iri(rURI.toString());
				
				List<Statement> toRemove = new ArrayList<Statement>();
				List<Statement> toAdd = new ArrayList<Statement>();

				for (Statement rdfsTypeStmnt : metadata.filter(null, iri("http://www.w3.org/TR/rdf-schema/type"), null)) {
					if (rdfsTypeStmnt.getObject() instanceof Resource) {
						String objValue = rdfsTypeStmnt.getObject().stringValue();
						if (objValue.startsWith("http://purl.org/telmap/") || // fix for telmap
								objValue.equals("http://http://xmlns.com/foaf/0.1/Person")) { // fix for voa3r
							toAdd.add(getValueFactory().createStatement(rdfsTypeStmnt.getSubject(), RDF.TYPE, rdfsTypeStmnt.getObject()));
							toRemove.add(rdfsTypeStmnt);
						}
					}
				}
				
				// Logging and graph modification
				
				if (!toAdd.isEmpty()) {
					log.info("ADD");
					for (Statement statement : toAdd) {
						log.info(statement.toString());
					}
					if (!metadata.addAll(toAdd)) {
						log.info("STRANGE: Graph not modified");
					} else {
						updateNecessary = true;
					}
				}

				if (!toRemove.isEmpty()) {
					log.info("DEL");
					for (Statement statement : toRemove) {
						log.info(statement.toString());
					}
					if (!metadata.removeAll(toRemove)) {
						log.info("STRANGE: Graph not modified");
					} else {
						updateNecessary = true;
					}
				}

				if (updateNecessary) {
					// set the updated graph
					localMd.setGraph(metadata);
					log.info("----- Updated metadata of entry: " + entry.getEntryURI());
				}
			}
		}
	}
	
	private static Set<Date> getStrangeDates(Entry entry) {
		Set<Date> result = new HashSet<>();
		ValueFactory vf = SimpleValueFactory.getInstance();
		
		Date from = parseDateFromStringStrict("1910-01-01");
		Date until = parseDateFromStringStrict("2009-12-01");
		IRI dctermsDate = vf.createIRI(NS.dcterms + "date");
		IRI w3cdtf = vf.createIRI("http://purl.org/dc/terms/W3CDTF");
		
		Metadata localMd = entry.getLocalMetadata();
		if (localMd != null) {
			Model metadata = entry.getLocalMetadata().getGraph();
			if (metadata != null) {
				for (Statement statement : metadata.filter(null, dctermsDate, null)) {
					Value obj = statement.getObject();
					if (obj instanceof Literal lit) {
						if (!w3cdtf.equals(lit.getDatatype())) {
							log.info("STRANGE: incorrect datatype!");
						}
						Date date = parseDateFromStringStrict(lit.stringValue());
						if (date.before(from) || date.after(until)) {
							result.add(date);
						}
					}
				}
			}
		}
		return result;
	}
	
	private static Date parseDateFromStringStrict(String dateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");//'T'HH:mm:ssZ");
			Date d = sdf.parse(dateString);
			//log("Matched: yyyy-MM-dd'T'HH:mm:ssZ :: " + dateString);
			return d;
		} catch (ParseException pe) {
			log.info(pe.getMessage());
		}
		return null;
	}
	
	private static String dateToString(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		return sdf.format(date);
	}
	
	private static Date parseDateFromString(String dateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			Date d = sdf.parse(dateString);
			log.info("Matched: yyyy-MM-dd'T'HH:mm:ssZ :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		
		Set<String> formats = new HashSet<String>();
		formats.add("dd MMMMM yyyy");
		formats.add("MMMMM yyyy");
		formats.add("dd.MM.yy");
		formats.add("dd/MM/yyyy");
		formats.add("dd-MM-yyyy");
		formats.add("dd-MMMMM-yyyy");
		formats.add("ddMMyyyy");
		formats.add("yyyy-MM-dd");
		
		SimpleDateFormat sdf = null;
		for (String format : formats) {
			sdf = new SimpleDateFormat(format, Locale.ENGLISH);
			try {
				Date d = sdf.parse(dateString);
				log.info("Matched: " + format + " :: " + dateString);
				return d;
			} catch (ParseException pe) {}
		}
		
		// the following patterns cannot be included in the Set above because the order matters
		try {
			sdf = new SimpleDateFormat("dd.MM.yyyy");
			Date d = sdf.parse(dateString);
			log.info("Matched: dd.MM.yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("MM/yyyy");
			Date d = sdf.parse(dateString);
			log.info("Matched: MM/yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("MM-yyyy");
			Date d = sdf.parse(dateString);
			log.info("Matched: MM-yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("yyyy-MM");
			Date d = sdf.parse(dateString);
			log.info("Matched: yyyy-MM :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("yyyy");
			Date d = sdf.parse(dateString);
			log.info("Matched: yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		
		log.info("UNMATCHED: " + dateString);
		return null;
	}
	
	public void checkAllDates() {
		URI currentUser = pm.getAuthenticatedUserURI();
		Writer writer = null;
		try {
			writer = new FileWriter(new File("/home/hannes/Desktop/dates.txt"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				Set<Date> strangeDates = getStrangeDates(entry);
				if (!strangeDates.isEmpty()) {
					String datesStr = "";
					for (Date date : strangeDates) {
						datesStr += dateToString(date) + ";";
					}
					String logMsg = "URI: " + entry.getEntryURI() + ", Title: \"" + EntryUtil.getTitle(entry, "en") + "\", suspicious date(s): " + datesStr;
					log.info(logMsg);
					try {
						writer.write(logMsg + "\n");
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		try {
			writer.flush();
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void fixMetadataGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				if (!entry.getEntryType().equals(EntryType.Reference)) {// && !entry.getResourceType().equals(ResourceType.None)) {
					fixMetadataOfEntry(entry);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void fixPrincipalsGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			List<User> users = pm.getUsers();
			for (User user : users) {
				Entry entry = user.getEntry();
				if (entry != null) {
					fixMetadataOfPrincipal(entry);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void convertPasswordsToHashes() {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			List<User> users = pm.getUsers();
			for (User user : users) {
				String secret = user.getSecret();
				if (secret != null) {
					log.warn("Replacing password with salted hashed password for user " + user.getURI());
					if (!user.setSecret(secret)) {
						log.error("Unable to reset password of user " + user.getURI());
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void printFileNamesGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				if (entry.getEntryType().equals(EntryType.Local)) {
					if (entry.getFilename() != null) {
						log.info(entry.getFilename());
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}

	/**
	 * Removes all triples in all EntryStore contexts' Sesame contexts that are used to track deleted entries.
	 *
	 * @param repository
	 */
	public static void cleanupTrackedDeletedEntries(Repository repository) {
		RepositoryConnection rc = null;
		try {
			rc = repository.getConnection();
			Date before = new Date();
			log.info("Performing cleanup of tracked deleted entries");
			long tripleCountBefore = rc.size();
			rc.begin();
			log.info("Removing all triples with predicate " + RepositoryProperties.Deleted);
			rc.remove(rc.getStatements((Resource) null, RepositoryProperties.Deleted, (Value) null, false));
			log.info("Removing all triples with predicate " + RepositoryProperties.DeletedBy);
			rc.remove(rc.getStatements((Resource) null, RepositoryProperties.DeletedBy, (Value) null, false));
			rc.commit();
			log.info("Cleanup removed " + (tripleCountBefore - rc.size()) + " triples and took " + (new Date().getTime() - before.getTime()) + " ms");
		} catch (RepositoryException e) {
			try {
				rc.rollback();
			} catch (RepositoryException re) {
				log.error(re.getMessage());
			}
			log.error(e.getMessage());
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
	}
	
}
