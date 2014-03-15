/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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


package org.entrystore.repository.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import org.apache.commons.io.FileUtils;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.ResourceType;
import org.entrystore.Resource;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.NS;
import org.entrystore.AuthorizationException;
import org.entrystore.repository.security.DisallowedException;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.repository.util.URISplit;
import org.entrystore.repository.util.URISplit.URIType;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.Binding;
import org.openrdf.query.BindingSet;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Matthias Palmer
 * @author Hannes Ebner
 */
public class ContextManagerImpl extends EntryNamesContext implements ContextManager {

	Logger log = LoggerFactory.getLogger(ContextManagerImpl.class);
	
	private EntryImpl allContexts;

	public ContextManagerImpl(RepositoryManagerImpl rman, Repository repo) {
		super(new EntryImpl(rman,repo), URISplit.fabricateURI(rman.getRepositoryURL().toString(), 
				RepositoryProperties.SYSTEM_CONTEXTS_ID, 
				RepositoryProperties.DATA_PATH, 
				RepositoryProperties.SYSTEM_CONTEXTS_ID).toString(), 
				rman.getSoftCache());
		//First try to load the ContextManager.
		EntryImpl e = (EntryImpl) getByEntryURI(URISplit.fabricateURI(rman.getRepositoryURL().toString(), 
				RepositoryProperties.SYSTEM_CONTEXTS_ID, 
				RepositoryProperties.ENTRY_PATH, 
				RepositoryProperties.SYSTEM_CONTEXTS_ID));
		//If it does not exist, create it.
		if (e == null) {
			e = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.SystemContext,
					null, rman.getSystemContextAliases().get(0));
		}
		this.entry = e;
		loadIndex();
	}

	public boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			for (int i=0; i<files.length; i++) {
				if (files[i].isDirectory()) {
					deleteDirectory(files[i]);
				} else {
					files[i].delete();
				}
			}
		}
		return path.delete();
	}
	
	public void deleteContext(URI contextURI) throws RepositoryException {
		if (contextURI == null) {
			throw new IllegalArgumentException("Context URI must not be null");
		}
		
		String contextURIStr = contextURI.toString();
		if (!contextURIStr.endsWith("/")) {
			contextURIStr += "/";
		}
		
		Entry contextEntry = getEntry(contextURI);
		String contextId = contextEntry.getId();
		
		synchronized (this.entry.repository) {
			RepositoryConnection rc = null;

			try {
				log.info("Removing context " + contextURI + " from index");
				remove(contextURI);
				
				rc = entry.getRepository().getConnection();
				rc.setAutoCommit(false);
				
				ValueFactory vf = rc.getValueFactory();
				
				RepositoryResult<org.openrdf.model.Resource> availableNGs = rc.getContextIDs();
				List<org.openrdf.model.Resource> filteredNGs = new ArrayList<org.openrdf.model.Resource>();
				for (org.openrdf.model.Resource ng : availableNGs.asList()) {
					if (ng.toString().startsWith(contextURIStr)) {
						filteredNGs.add(ng);
					}
				}
				
				org.openrdf.model.Resource[] filteredNGsArray = filteredNGs.toArray(new org.openrdf.model.Resource[filteredNGs.size()]);
				if (filteredNGsArray == null || filteredNGsArray.length == 0) {
					log.warn("No named graphs matching this context");
					return;
				}
				
				// remove all triples belonging to the context
				
				org.openrdf.model.URI contextURISesame = vf.createURI(contextURI.toString());
				org.openrdf.model.URI baseURI = vf.createURI(entry.getRepositoryManager().getRepositoryURL().toString());
				String baseURIStr = baseURI.toString();
				
				List<Statement> stmntsToRemove = rc.getStatements(null, null, null, false, filteredNGsArray).asList();
				log.info("Removing " + stmntsToRemove.size() + " triples contained in context " + contextURI);
				rc.remove(stmntsToRemove);
				
				List<Statement> referencesToRemove = rc.getStatements(null, null, contextURISesame, false).asList();
				referencesToRemove.addAll(rc.getStatements(contextURISesame, null, null, false).asList());
				
				// remove all triples in named graphs which look like: http://base/_contexts/{kind}/{context-id}
				
				referencesToRemove.addAll(rc.getStatements(null, null, null, false, vf.createURI(URISplit.fabricateURI(baseURIStr, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, contextId).toString())).asList());
				referencesToRemove.addAll(rc.getStatements(null, null, null, false, vf.createURI(URISplit.fabricateURI(baseURIStr, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.MD_PATH, contextId).toString())).asList());
				referencesToRemove.addAll(rc.getStatements(null, null, null, false, vf.createURI(URISplit.fabricateURI(baseURIStr, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.DATA_PATH, contextId).toString())).asList());
				referencesToRemove.addAll(rc.getStatements(vf.createURI(URISplit.fabricateURI(baseURIStr, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, contextId).toString()), null, null, false).asList());
				
				log.info("Removing " + referencesToRemove.size() + " references to context " + contextURI);
				rc.remove(referencesToRemove);
				
				rc.commit();
				
				// recursively remove the file directory on the hard disk
				
				String contextPath = this.entry.getRepositoryManager().getConfiguration().getString(Settings.DATA_FOLDER);
	            if (contextPath != null) {
	            	File contextPathFile = new File(URI.create(contextPath));
	            	File contextFolder = new File(contextPathFile, contextId);
	            	if (contextFolder.isDirectory() && contextFolder.canWrite()) {
	            		log.info("Removing all local files referenced by context " + contextURI);
	            		FileOperations.deleteAllFilesInDir(contextFolder);
	            		contextFolder.delete();
	            	} else {
	            		log.error("The data path of context " + contextId + " is not a folder or not writable: " + contextFolder);
	            	}
	            } else {
	            	log.error("No SCAM data folder configured");
	            }
			} catch (RepositoryException e) {
				rc.rollback();
				log.error("Error when deleting context", e);
				throw e;
			} finally {
				if (rc != null) {
					rc.close();
				}
			}
		}
	}
	
	/**
	 * @see org.entrystore.ContextManager#exportContext(org.entrystore.Entry, java.io.File, java.util.Set, boolean)
	 */
	public void exportContext(Entry contextEntry, File destFile, Set<URI> users, boolean metadataOnly, Class<? extends RDFWriter> writer) throws RepositoryException {
		String contextResourceURI = contextEntry.getResourceURI().toString();
		if (!contextResourceURI.endsWith("/")) {
			contextResourceURI += "/";
		}
		String contextEntryURI = contextEntry.getEntryURI().toString();
		String contextMetadataURI = contextEntry.getLocalMetadataURI().toString();
		String contextRelationURI = contextEntry.getRelationURI().toString();

		synchronized (this.entry.repository) {
			RepositoryConnection rc = null;
			BufferedOutputStream out = null;

			try {
				try {
					out = new BufferedOutputStream(new FileOutputStream(destFile));
				} catch (FileNotFoundException e) {
					log.error(e.getMessage());
					throw new RepositoryException(e);
				}

				rc = entry.getRepository().getConnection();
				
				RepositoryResult<org.openrdf.model.Resource> availableNGs = rc.getContextIDs();
				List<org.openrdf.model.Resource> filteredNGs = new ArrayList<org.openrdf.model.Resource>();
				while (availableNGs.hasNext()) {
					org.openrdf.model.Resource ng = availableNGs.next();
					String ngURI = ng.stringValue();
					if (metadataOnly) {
						if (ngURI.startsWith(contextResourceURI) &&	(ngURI.contains("/metadata/") || ngURI.contains("/cached-external-metadata/"))) {
							filteredNGs.add(ng);
						}
					} else {
						if (ngURI.startsWith(contextResourceURI) ||
								ngURI.equals(contextEntryURI) ||
								ngURI.equals(contextMetadataURI) ||
								ngURI.equals(contextRelationURI)) {
							filteredNGs.add(ng);
						}
					}
				}

				RDFHandler rdfWriter = null;
				try {
					Constructor<? extends RDFWriter> constructor = writer.getConstructor(OutputStream.class);
					rdfWriter = (RDFWriter) constructor.newInstance(out);
				} catch (Exception e) {
					log.error(e.getMessage());
				}
				
				if (rdfWriter == null) {
					log.error("Unable to create an RDF writer, format not supported");
					return;
				}
				
				Map<String, String> namespaces = NS.getMap();
				for (String nsName : namespaces.keySet()) {
					rdfWriter.handleNamespace(nsName, namespaces.get(nsName));
				}
				
				rdfWriter.startRDF();
				RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, filteredNGs.toArray(new org.openrdf.model.Resource[filteredNGs.size()]));
				while (rr.hasNext()) {
					Statement s = rr.next();
					org.openrdf.model.URI p = s.getPredicate();
					rdfWriter.handleStatement(s);
					if (!metadataOnly) {
						if (p.equals(RepositoryProperties.Creator) ||
								p.equals(RepositoryProperties.Contributor) ||
								p.equals(RepositoryProperties.Read) ||
								p.equals(RepositoryProperties.Write) ||
								p.equals(RepositoryProperties.DeletedBy)) {
							users.add(URI.create(s.getObject().stringValue()));
						}
					}
				}
				rdfWriter.endRDF();
			} catch (RepositoryException e) {
				log.error("Error when exporting context", e);
				throw e;
			} catch (RDFHandlerException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException(e);
			} finally {
				rc.close();
				try {
					out.flush();
					out.close();
				} catch (IOException ignored) {}
			}
		}
	}
	
	public void importContext(Entry contextEntry, File srcFile) throws RepositoryException, IOException {
		Date before = new Date();
		
		File unzippedDir = FileOperations.createTempDirectory("scam_import", null);
		FileOperations.unzipFile(srcFile, unzippedDir);
		
		File propFile = new File(unzippedDir, "export.properties");
		log.info("Loading property file from " + propFile.toString());
		Properties props = new Properties();
		props.load(new FileInputStream(propFile));
		String srcScamBaseURI = props.getProperty("scamBaseURI");
		String srcContextEntryURI = props.getProperty("contextEntryURI");
		String srcContextResourceURI = props.getProperty("contextResourceURI");
		String srcContextMetadataURI = props.getProperty("contextMetadataURI");
		String srcContextRelationURI = props.getProperty("contextRelationURI");
		String srcContainedUsers = props.getProperty("containedUsers");
		
		if (srcScamBaseURI == null || srcContextEntryURI == null || srcContextResourceURI == null || srcContainedUsers == null) {
			String msg = "Property file of import ZIP did not contain all necessary properties, aborting import"; 
			log.error(msg);
			throw new org.entrystore.repository.RepositoryException(msg);
		}
		
		log.info("scamBaseURI: " + srcScamBaseURI);
		log.info("contextEntryURI: " + srcContextEntryURI);
		log.info("contextResourceURI: " + srcContextResourceURI);
		log.info("contextMetadataURI: " + srcContextMetadataURI);
		log.info("contextRelationURI: " + srcContextRelationURI);
		log.info("containedUsers: " + srcContainedUsers);
		
		List<String> containedUsers = Arrays.asList(srcContainedUsers.split(","));
		Map<String, String> id2name = new HashMap<String, String>();
		for (String u : containedUsers) {
			String[] uS = u.split(":");
			if (uS.length == 1) {
				id2name.put(uS[0], null);
			} else if (uS.length == 2) {
				id2name.put(uS[0], uS[1]);
			}
		}
		
		// remove entries from context
		
		log.info("Removing old entries from context...");
		Context cont = getContext(contextEntry.getId());
		Set<URI> entries = cont.getEntries();
		for (URI entryURI : entries) {
			String eId = cont.getByEntryURI(entryURI).getId();
			if (!eId.startsWith("_")) {
				log.info("Removing " + entryURI);
				try {
					cont.remove(entryURI);
				} catch (DisallowedException de) {
					log.warn(de.getMessage());
				}
			}
		}
		
		// copy resources/files to data dir of context
		
		File dstDir = new File(entry.getRepositoryManager().getConfiguration().getString(Settings.DATA_FOLDER), contextEntry.getId());
		if (!dstDir.exists()) {
			dstDir.mkdirs();
		}
		File resourceDir = new File(unzippedDir, "resources");
		if (resourceDir != null && resourceDir.exists() && resourceDir.isDirectory()) {
			for (File src : resourceDir.listFiles()) {
				File dst = new File(dstDir, src.getName());
				log.info("Copying " + src + " to " + dst);
				FileOperations.copyFile(src, dst);
			}
		}
		
		// load all statements from file
		
		long amountTriples = 0;
		long importedTriples = 0;
		File tripleFile = new File(unzippedDir, "triples.rdf");
		log.info("Loading quadruples from " + tripleFile.toString());
		InputStream rdfInput = new BufferedInputStream(new FileInputStream(tripleFile));
		
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		
		synchronized (this.entry.repository) {
			log.info("Importing context from stream");
			RepositoryConnection rc = null;
			try {
				rc = entry.getRepository().getConnection();
				rc.setAutoCommit(false);
				
				TriGParser parser = new TriGParser();
				parser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);
				StatementCollector collector = new StatementCollector();
				parser.setRDFHandler(collector);
				parser.parse(rdfInput, srcScamBaseURI);
				
				String oldBaseURI = srcScamBaseURI;
				if (!oldBaseURI.endsWith("/")) {
					oldBaseURI += "/";
				}
				String newBaseURI = entry.getRepositoryManager().getRepositoryURL().toString();
				if (!newBaseURI.endsWith("/")) {
					newBaseURI += "/";
				}
				String oldContextID = srcContextResourceURI.substring(srcContextResourceURI.lastIndexOf("/") + 1);
				String newContextID = contextEntry.getId();
				
				String oldContextResourceURI = srcContextResourceURI;
				String oldContextEntryURI = srcContextEntryURI;
				String oldContextMetadataURI = srcContextMetadataURI;
				String oldContextRelationURI = srcContextRelationURI;
				String oldContextNS = oldContextResourceURI;
				if (!oldContextNS.endsWith("/")) {
					oldContextNS += "/";
				}
				String newContextNS = contextEntry.getResourceURI().toString();
				if (!newContextNS.endsWith("/")) {
					newContextNS += "/";
				}
				
				log.info("Old context ID: " + oldContextID);
				log.info("New context ID: " + newContextID);
				log.info("Old context resource URI: " + oldContextResourceURI);
				log.info("New context resource URI: " + contextEntry.getResourceURI().toString());
				log.info("Old context entry URI: " + oldContextEntryURI);
				log.info("New context entry URI: " + contextEntry.getEntryURI().toString());
				log.info("Old context metadata URI: " + oldContextMetadataURI);
				log.info("New context metadata URI: " + contextEntry.getLocalMetadataURI().toString());
				log.info("Old context relation URI: " + oldContextRelationURI);
				log.info("New context relation URI: " + contextEntry.getRelationURI().toString());

				// iterate over all statements and add them to the reposivory
				
				ValueFactory vf = rc.getValueFactory();
				for (Statement s : collector.getStatements()) {
					amountTriples++;
					org.openrdf.model.Resource context = s.getContext();
					if (context == null) {
						log.warn("No named graph information provided, ignoring triple");
						continue;
					}

					org.openrdf.model.Resource subject = s.getSubject();
					org.openrdf.model.URI predicate = s.getPredicate();
					Value object = s.getObject();

					if (predicate.equals(RepositoryProperties.Creator) ||
							predicate.equals(RepositoryProperties.Contributor) ||
							predicate.equals(RepositoryProperties.Read) ||
							predicate.equals(RepositoryProperties.Write) ||
							predicate.equals(RepositoryProperties.DeletedBy)) {
						String oldUserID = object.stringValue().substring(object.stringValue().lastIndexOf("/") + 1);
						log.info("Old user URI: " + object);
						log.info("Old user ID: " + oldUserID);
						String oldUserName = id2name.get(oldUserID);
						URI newUserURI = null;
						Entry pE = null;
						if (oldUserName == null) {
							pE = pm.get(oldUserID);						
						} else {
							pE = pm.getPrincipalEntry(oldUserName);
						}
						if (pE != null) {
							newUserURI = pE.getResourceURI();
						}
						if (newUserURI == null) {
							log.info("Unable to detect principal for ID " + oldUserID + ", skipping");
							continue;
						}
						object = vf.createURI(newUserURI.toString());
						log.info("Created principal URI for user " + oldUserID + ":" + oldUserName + ": " + object.stringValue());
					}

					if (subject instanceof org.openrdf.model.URI) {
						String sS = subject.stringValue();
						if (sS.startsWith(oldContextNS)) {
							subject = vf.createURI(sS.replace(oldContextNS, newContextNS));
						} else if (sS.equals(oldContextEntryURI)) {
							subject = vf.createURI(contextEntry.getEntryURI().toString());
						} else if (sS.equals(oldContextResourceURI)) {
							subject = vf.createURI(contextEntry.getResourceURI().toString());
						} else if (sS.equals(oldContextMetadataURI)) {
							subject = vf.createURI(contextEntry.getLocalMetadataURI().toString());
						} else if (sS.equals(oldContextRelationURI)) {
							subject = vf.createURI(contextEntry.getRelationURI().toString());
						}
					}

					if (object instanceof org.openrdf.model.URI) {
						String oS = object.stringValue();
						if (oS.startsWith(oldContextNS)) {
							object = vf.createURI(oS.replace(oldContextNS, newContextNS));
						} else if (oS.equals(oldContextEntryURI)) {
							object = vf.createURI(contextEntry.getEntryURI().toString());
						} else if (oS.equals(oldContextResourceURI)) {
							object = vf.createURI(contextEntry.getResourceURI().toString());
						} else if (oS.equals(oldContextMetadataURI)) {
							object = vf.createURI(contextEntry.getLocalMetadataURI().toString());
						} else if (oS.equals(oldContextRelationURI)) {
							object = vf.createURI(contextEntry.getRelationURI().toString());
						}
					}

					if (context instanceof org.openrdf.model.URI) {
						String cS = context.stringValue();
						
//						// dirty hack to skip metadata on portfolio
//						if (cS.contains("/_contexts/metadata/")) {
//							log.info("Skipping metadata triple for portfolio: " + s.toString());
//							continue;
//						}
						
						if (cS.startsWith(oldContextNS)) {
							context = vf.createURI(cS.replace(oldContextNS, newContextNS));
						} else if (cS.equals(oldContextEntryURI)) {
							context = vf.createURI(contextEntry.getEntryURI().toString());
						} else if (cS.equals(oldContextResourceURI)) {
							context = vf.createURI(contextEntry.getResourceURI().toString());
						} else if (cS.equals(oldContextMetadataURI)) {
							context = vf.createURI(contextEntry.getLocalMetadataURI().toString());
						} else if (cS.equals(oldContextRelationURI)) {
							context = vf.createURI(contextEntry.getRelationURI().toString());
						}
					}

					// we check first whether such a stmnt already exists 
					Statement newStmnt = vf.createStatement(subject, predicate, object, context);
					if (!rc.hasStatement(newStmnt, false, context)) {
						importedTriples++;
						log.info("Adding statement to repository: " + newStmnt.toString());
						rc.add(newStmnt, context);
					} else {
						log.warn("Statement already exists, skipping: " + newStmnt.toString());
					}
				}
				
				rc.commit();
			} catch (Exception e) {
				rc.rollback();
				log.error(e.getMessage(), e);
			} finally {
				if (rc != null) {
					rc.close();
				}
			}
		}
		
		// clean up temp files
				
		log.info("Removing temporary files");
		for (File f : unzippedDir.listFiles()) {
			if (f.isDirectory()) {
				FileOperations.deleteAllFilesInDir(f);
			}
			f.delete();
		}
		unzippedDir.delete();
		
		// reindex the context to get everything reloaded
		
		log.info("Reindexing " + cont.getEntry().getEntryURI());
		cont.reIndex();
		
		log.info("Import finished in " + (new Date().getTime() - before.getTime()) + " ms");
		log.info("Imported " + importedTriples + " triples");
		log.info("Skipped " + (amountTriples - importedTriples) + " triples");
	}

	/** FIXME: rewrite
	 */
	public boolean deleteBackup(URI contexturi, String fromTime) {
		String folder = getContextBackupFolder(contexturi, fromTime); 
		File backupFolder = new File(folder);
		if(backupFolder.exists()) {
			return deleteDirectory(backupFolder);  
		} else {
			log.error("The folder does not exist"); 
		}
		return false;
	}

	/** FIXME: rewrite
	 */
	public String getBackup(URI contexturi, String fromTime) {
		String folder = getContextBackupFolder(contexturi, fromTime); 
		try {
			File file = new File(folder, "portfolio-index.rdf");
			File file2 = new File(folder,  "portfolio-entries.rdf");

			String content1 = FileUtils.readFileToString(file);
			String content2 = FileUtils.readFileToString(file2);

			return new String(content1 + content2); 			            

		}catch (IOException e) {
			log.error(e.getMessage()); 
		} 
		return null;
	}

	/** FIXME: rewrite
	 */
	public String createBackup(URI contexturi) throws RepositoryException {
		HashMap<String, String> map = getContextBackupFolder(contexturi, new Date()); 
		File backupFolder = new File(map.get("dateFolder"));

		backupFolder.mkdirs();

		RepositoryConnection conn = null;
		try {
			conn = entry.getRepository().getConnection();
			Entry portfolio = getEntry(contexturi);

			RDFHandler indexHandler = null;
			try {
				indexHandler = new TriGWriter(new FileOutputStream(new File(backupFolder, "portfolio-index.rdf")));
			} catch (FileNotFoundException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException(e);
			}

			ValueFactory f = entry.getRepository().getValueFactory();
			try {
				conn.export(indexHandler, f.createURI(portfolio.getEntryURI().toString()));
				conn.export(indexHandler, f.createURI(portfolio.getLocalMetadataURI().toString()));
				conn.export(indexHandler, f.createURI(portfolio.getResourceURI().toString()));
			} catch (RDFHandlerException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException(e);
			}

			Context pfContext = (Context) portfolio.getResource();

			RDFHandler entryHandler;
			try {
				entryHandler = new TriGWriter(new FileOutputStream(new File(backupFolder, "portfolio-entries.rdf")));
			} catch (FileNotFoundException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException(e);
			}

			try {
				Set<URI> portfolioResources = pfContext.getEntries();
				for (URI uri : portfolioResources) {
					Entry e = pfContext.getByEntryURI(uri);
					conn.export(entryHandler, f.createURI(e.getEntryURI().toString()));
					if (e.getEntryType() == EntryType.Local || e.getEntryType() == EntryType.Link
							|| e.getEntryType() == EntryType.LinkReference) {
						conn.export(entryHandler, f.createURI(e.getLocalMetadata().getURI().toString()));
					}
					if (e.getEntryType() == EntryType.Local && e.getGraphType() != GraphType.None) {
						conn.export(entryHandler, f.createURI(e.getResourceURI().toString()));
					} else if (e.getEntryType() == EntryType.Local && e.getGraphType() == GraphType.None
							&& e.getResourceType() == ResourceType.InformationResource) {
						// File dataFolder = new
						// File(entry.getRepositoryManager().getConfiguration().getString(Settings.SCAM_DATA_FOLDER));
						// TODO Spara undan filen som ligger i entryt.
						// Det som står här under stämmer inte.
						// String id = new URISplit(uri,
						// e.getRepositoryManager().getRepositoryURL()).getID();
						// File src = new File(dataFolder, id);
						// File dest = new File(backupFolder, id);
						// try {
						// FileOperations.copyFile(src, dest);
						// } catch (IOException ioe) {
						// log.error(ioe.getMessage(), ioe);
						// throw new RepositoryException(ioe);
						// }
					}
				}
			} catch (RDFHandlerException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException(e);
			}
		} catch (RepositoryException e) {
			log.error("Error in repository", e);
			throw e;
		} finally {
			conn.close();
		}
		return map.get("timestampStr");
	}

	/** FIXME: rewrite
	 */
	public List<Date> listBackups(URI contexturi) {
		File dir = new File(getContextBackupFolder(contexturi));
		String[] children = dir.list();
		List<Date> backups = new ArrayList<Date>();
		for (int i = 0; i < children.length; i++) {
			Date bkp = parseTimestamp(children[i]);
			if (bkp != null) {
				backups.add(bkp);
			}
		}
		return backups;
	}

	/** FIXME: rewrite
	 */
	public void restoreBackup(URI contexturi, String fromTime) {
		this.remove(this.getByResourceURI(contexturi).iterator().next().getEntryURI()); 

		TriGParser trigParser = new TriGParser();
		trigParser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);

		StatementCollector collector = new StatementCollector();
		trigParser.setRDFHandler(collector);
		String folder = getContextBackupFolder(contexturi, fromTime); 
		try {
			FileInputStream fileOut = new FileInputStream( new File(folder, "portfolio-index.rdf"));
			FileInputStream fileOut2 = new FileInputStream(new File(folder,  "portfolio-entries.rdf"));

			String base = entry.getRepositoryManager().getConfiguration().getString(Settings.BASE_URL, "http://scam4.org");

			trigParser.parse(fileOut, base);
			fileOut.close();
			RepositoryConnection conn = entry.getRepository().getConnection();
			try {
				for (Statement s : collector.getStatements()) {
					conn.add(s); 
				}

				trigParser.parse(fileOut2, base);
				fileOut2.close();
				for (Statement s : collector.getStatements()) {
					conn.add(s); 
				}
			} finally {
				conn.close();
			}

		} catch (FileNotFoundException e) {
			log.error(e.getMessage()); 
		} catch (RDFHandlerException e) {
			log.error(e.getMessage()); 
		} catch (RDFParseException e) {
			log.error(e.getMessage()); 
		} catch (IOException e) {
			log.error(e.getMessage()); 
		} catch (RepositoryException e) {
			e.printStackTrace();
			log.error(e.getMessage()); 
		} 

	}

	/** FIXME: rewrite
	 */
	public String getContextBackupFolder(URI contexturi) {
		String backupFolder = entry.getRepositoryManager().getConfiguration().getString(Settings.BACKUP_FOLDER);
		String helper = contexturi.toString();

		// TODO use URIStr instead - but we don't have the baseURL

		String pfId = helper.substring(helper.lastIndexOf("/")+1);
		if (!backupFolder.endsWith("/")) {
			backupFolder += "/";
		}
		return backupFolder + pfId;
	}

	public HashMap<String, String> getContextBackupFolder(URI contexturi, Date date) {
		String timestampStr = new SimpleDateFormat("yyyyMMddHHmmss").format(date);
		File backupFolder = new File(getContextBackupFolder(contexturi));
		File datedFolder = new File(backupFolder, timestampStr);

		HashMap<String, String> map = new HashMap<String,String>(); 
		map.put("dateFolder", datedFolder.toString());
		map.put("timestampStr", timestampStr); 
		return map; 
	}

	public String getContextBackupFolder(URI contexturi, String date) {
		String timestampStr = date; 
		File backupFolder = new File(getContextBackupFolder(contexturi));
		File datedFolder = new File(backupFolder, timestampStr);

		return datedFolder.toString(); 
	}

	private Date parseTimestamp(String timestamp) {
		Date date = null;
		DateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		try {
			date = formatter.parse(timestamp);
		} catch (ParseException pe) {
			log.error(pe.getMessage()); 
		}
		return date;
	}

	public String getContextAlias(URI contextURI) {
		URISplit us = new URISplit(contextURI, this.entry.getRepositoryManager().getRepositoryURL());
		if (us.getURIType() == URIType.Resource) {
			return getName(us.getMetaMetadataURI());
		}
		throw new org.entrystore.repository.RepositoryException("Given URI is not an existing contextURI.");
	}

	public URI getContextURI(String contextAlias) {
		Entry contextEntry = getEntryByName(contextAlias);
		if (contextEntry == null) {
			return null;
		} else if (contextEntry.getGraphType() == GraphType.Context ||
						contextEntry.getGraphType() == GraphType.SystemContext) {
			return contextEntry.getResourceURI();
		}
		throw new org.entrystore.repository.RepositoryException("Found entry for the alias is not a context...\n" +
				"this is either a programming error or someone have been tampering with the RDF directly.");
	}

	public Set<String> getContextAliases() {
		return getEntryNames();
	}

	public boolean setContextAlias(URI contextURI, String newAlias) {
		URISplit us = new URISplit(contextURI, this.entry.getRepositoryManager().getRepositoryURL());
		Entry contextEntry = getByEntryURI(us.getMetaMetadataURI());
		if (contextEntry == null) {
			throw new org.entrystore.repository.RepositoryException("Cannot find an entry for the specified URI");
		} else if (contextEntry.getGraphType() == GraphType.Context ||
				contextEntry.getGraphType() == GraphType.SystemContext) {
			return setEntryName(us.getMetaMetadataURI(), newAlias);
		}
		throw new org.entrystore.repository.RepositoryException("Given URI does not refer to a Context.");
	}

	/**
	 * @param contextId
	 */
	public Context getContext(String contextId) {
		Entry entry = this.get(contextId);
		if (entry == null) { //By name
			entry = getEntryByName(contextId);
		}
/*		if (entry == null) { //Maybe it is a full URI, lets try.
			try {
				URI uri = new URI(contextId);
				entry = getByEntryURI(uri);
				if (entry == null) {
					Set<Entry> set = getByResourceURI(uri);
					if (!set.isEmpty()) {
						entry = set.iterator().next();
					}
				}
			} catch (URISyntaxException e) {
			}
		}*/
		if(entry != null) {
			if (entry.getGraphType() == GraphType.Context ||
					entry.getGraphType() == GraphType.SystemContext) {
				Resource context = entry.getResource();
				if (context != null && context instanceof Context) {
					return (Context) context;
				}
			}
		}
		return null;
	}

	//Retrieve functions
	public Entry getEntry(URI uri) {
		URISplit usplit = new URISplit(uri, entry.getRepositoryManager().getRepositoryURL());
		if (usplit.getURIType() != URIType.Unknown) {
			Entry item = cache.getByEntryURI(usplit.getMetaMetadataURI());
			if (item != null) {
				((ContextImpl) item.getContext()).checkAccess(item, AccessProperty.ReadMetadata);
				return item;
			}
			Entry contextEntry = getByEntryURI(usplit.getContextMetaMetadataURI());
			if (contextEntry != null) {
				return ((Context) contextEntry.getResource()).getByEntryURI(usplit.getMetaMetadataURI());
			} else {
				log.warn("No context found for Entry with URI " + uri);
			}
		}
		return null;
	}

	public Set<Entry> getLinks(URI resourceURI) {
		return getLinksOrReferences(resourceURI, true);
	}

	public Set<Entry> getReferences(URI metadataURI) {
		return getLinksOrReferences(metadataURI, false);
	}

	private Set<Entry> getLinksOrReferences(URI uri, boolean findLinks) {
		HashSet<Entry> entries = new HashSet<Entry>();
		try {
			RepositoryConnection rc = entry.repository.getConnection();
			try {
				ValueFactory vf = entry.repository.getValueFactory();
				org.openrdf.model.URI resource = vf.createURI(uri.toString());
				if (findLinks) {
					RepositoryResult<Statement> resources = rc.getStatements(resource, RepositoryProperties.resHasEntry, null, false);
					for (Statement statement: resources.asList()) {
						try {
							Entry entry = getItemInRepositoryByMMdURI(URI.create(statement.getObject().stringValue()));
							if (entry.getEntryType() == EntryType.Link) {
								entries.add(entry);
							}
						} catch (AuthorizationException ae) {
						}
					}
				} else {
					RepositoryResult<Statement> resources = rc.getStatements(resource, RepositoryProperties.mdHasEntry, null, false);
					for (Statement statement: resources.asList()) {
						try {
							Entry entry = getItemInRepositoryByMMdURI(URI.create(statement.getObject().stringValue()));
							if (entry.getEntryType() == EntryType.Reference) {
								entries.add(entry);
							}
						} catch (AuthorizationException ae) {
						}
					}
				}
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			log.error("Repository error", e);
			throw new org.entrystore.repository.RepositoryException("Repository error", e);
		}
		return entries;
	}

	protected Entry getItemInRepositoryByMMdURI(URI mmdURI) {
		Entry entry = cache.getByEntryURI(mmdURI);
		if (entry != null) {
			((ContextImpl) entry.getContext()).checkAccess(entry, AccessProperty.ReadMetadata);
			return entry;
		}

		Entry contextItem = getByEntryURI(Util.getContextMMdURIFromURI(this.entry.getRepositoryManager(), mmdURI));
		return ((Context) contextItem.getResource()).getByEntryURI(mmdURI);
	}
	
	public void initResource(EntryImpl newEntry) throws RepositoryException {
		if (newEntry.getEntryType() != EntryType.Local) {
			return;
		}

		switch (newEntry.getGraphType()) {
		case Context:
			Class clsReg = this.entry.repositoryManager.getRegularContextClass();
			try {
				Object[] constrParamReg = {newEntry, newEntry.getResourceURI().toString(), this.cache};
				Class[] constrClsParamReg = {EntryImpl.class, String.class, SoftCache.class};
				Constructor constrReg = clsReg.getConstructor(constrClsParamReg);		
				Context context = (Context) constrReg.newInstance(constrParamReg);
				newEntry.setResource(context);
//				context.initializeSystemEntries();
			} catch (Exception e) {
				throw new RepositoryException("Could not instantiate class "+clsReg.getName()
					+"\nfor regular Context with URI "+newEntry.getEntryURI());
			}
			break;
		case SystemContext:
			Class cls = this.entry.repositoryManager.getSystemContextClassForAlias(newEntry.getId());
			if (cls.isAssignableFrom(this.getClass())) { //Bootstrap case, i.e. when creating the ContextManager itself.
				newEntry.setResource(this);
			} else {
				try {
					Object[] constrParam = {newEntry, newEntry.getResourceURI().toString(), this.cache};
					Class[] constrClsParam = {EntryImpl.class, String.class, SoftCache.class};
					Constructor constr = cls.getConstructor(constrClsParam);
					newEntry.setResource((Context) constr.newInstance(constrParam)); 
					
				} catch (Exception e) {
					throw new RepositoryException("Could not instantiate class "+cls.getName()
							+"\nfor SystemContext with URI "+newEntry.getEntryURI());
				}
			}
			break;
		default:
			super.initResource(newEntry);
		}
	}

	/**
	 * Search in the repository after entries. The metadata query will be evaluated first.
	 * 
	 * @param mdQueryString a string which should be a SPARQL quary. The query result must
	 * return a URI to an entry. Can be null if you dont want to search metadata information.
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
	 * @param entryQueryString a SPARQL query which searchs after entries. 
	 * <pre>
	 * Example string: 
	 * PREFIX dc:<http://purl.org/dc/terms/> 
	 * SELECT ?entryURI 
	 * WHERE { 
	 * 		?entryURI dc:created ?y
	 * }
	 * </pre> 
	 * @param list a list with URI:s to contexts, if null all contexts will be returned. 
	 * Remember to take the context URI. Should be null if you want to search in all contexts.
	 * <pre>
	 * Example: 
	 * List<URI> list = new ArrayList<URI>(); 
	 * list.add(new URI("http://example.se/{context-id}"))
	 * </pre>  
	 * @return A list with entries or null.
	 * @throws Exception if something goes wrong
	 */
	public List<Entry> search(String entryQueryString, String mdQueryString, List<URI> contextList) throws Exception {
		List<Entry> mdEntries = null;
		List<Entry> entries = null;
		List<Entry> intersectionEntries = null; 

		//First we must evaluate the Metadata query.
		mdEntries = searchMetadataQuery(mdQueryString);  

		// Search in the entries.
		entries = searchEntryQuery(entryQueryString);

		// intersect the lists of entries. 
		intersectionEntries = intersectEntries(entries, mdEntries); 

		// Remove entries which do not contain the contexts which are in the context list
		List<Entry> foundEntries = intersectEntriesFromContexts(intersectionEntries, contextList);
		
		List<Entry> result = new ArrayList<Entry>();
		for (Entry entry : foundEntries) {
			if (entry != null && isEntryMetadataReadable(entry)) {
				result.add(entry);
			}
		}
		
		return result;
	}
	
	public Map<Entry, Integer> searchLiterals(Set<org.openrdf.model.URI> predicates, String[] terms, String lang, List<URI> context, boolean andOperation) {
		Map<org.openrdf.model.Resource, Integer> matches = new HashMap<org.openrdf.model.Resource, Integer>();
		RepositoryConnection rc = null;
		try {
			rc = entry.getRepository().getConnection();
			for (org.openrdf.model.URI p : predicates) {
				RepositoryResult<Statement> rr = rc.getStatements(null, p, null, false);
				while (rr.hasNext()) {
					Statement s = rr.next();
					Value o = s.getObject();
					if (!(o instanceof Literal)) {
						continue;
					}
					if (lang != null && !lang.equalsIgnoreCase(((Literal) o).getLanguage())) {
						continue;
					}
					org.openrdf.model.Resource c = s.getContext();
					if (context != null && context.size() > 0) {
						int contextMatches = 0;
						for (URI cURI : context) {
							if (cURI != null && c.stringValue().startsWith(cURI.toString())) {
								contextMatches++;
							}
						}
						if (contextMatches == 0) {
							continue;
						}
					}
					int matchCount = 0;
					for (String term : terms) {
						if (o.stringValue().toLowerCase().contains(term.toLowerCase())) {
							matchCount++;
						}
					}
					if (andOperation && (matchCount != terms.length)) {
						continue;
					}
					if (matches.containsKey(c)) {
						matches.put(c, matches.get(c) + matchCount);
					} else {
						matches.put(c, matchCount);
					}
				}
				rr.close();
			}
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException ignore) {}
			}
		}
		
		Map<Entry, Integer> result = new LinkedHashMap<Entry, Integer>();
		
		for (org.openrdf.model.Resource mdURI : matches.keySet()) {
			URISplit split = new URISplit(mdURI.stringValue(), entry.repositoryManager.getRepositoryURL());
			URI entryURI = split.getMetaMetadataURI();
			if (entryURI != null) {
				Entry e = null;
				try {
					e = getEntry(entryURI);
				} catch (AuthorizationException ae) {
					continue;
				}
				if (e != null && isEntryMetadataReadable(e)) {
					result.put(e, matches.get(mdURI));
				}
			}
		}
		
		if (result.size() > 1) {
			Date before = new Date();
			result = sortMapByValue(result, false);
			log.debug("Sorting results took: " + (new Date().getTime() - before.getTime()) + " ms");
		}
		
		return result;
	}
	
	public boolean isEntryMetadataReadable(Entry entry) {
		if (entry == null) {
			return false;
		}
		PrincipalManager pm = entry.getRepositoryManager().getPrincipalManager();
		try {
			//If linkReference or reference to a entry in the same repository
			//check that the referenced metadata is accessible.
			if ((entry.getEntryType() == EntryType.Reference
					|| entry.getEntryType() == EntryType.LinkReference)
					&& entry.getCachedExternalMetadata() instanceof LocalMetadataWrapper) {
				Entry refEntry = entry.getRepositoryManager().getContextManager().getEntry(entry.getExternalMetadataURI());
				pm.checkAuthenticatedUserAuthorized(refEntry, AccessProperty.ReadMetadata);							
			} else {
				//Check that the local metadata is accessible.
				pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
			}
		} catch (AuthorizationException ae) {
			return false;
		}
		return true;
	}

	public Map sortMapByValue(Map map, final boolean ascending) {
		List list = new LinkedList(map.entrySet());
		Collections.sort(list, new Comparator() {
			public int compare(Object o1, Object o2) {
				int result = ((Comparable) ((Map.Entry) (o1)).getValue()).compareTo(((Map.Entry) (o2)).getValue());
				if (result == 0) {
					result = ((Map.Entry) (o1)).getKey().toString().compareToIgnoreCase(((Map.Entry) (o2)).getKey().toString());
				}
				if (!ascending) {
					result *= -1;
				}
				return result;
			}
		});
		Map result = new LinkedHashMap();
		for (Iterator it = list.iterator(); it.hasNext();) {
			Map.Entry entry = (Map.Entry) it.next();
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	/**
	 * Intersection
	 * @param entries entries
	 * @param mdEntries entries from metadata query
	 * @return the intersected entries.
	 */
	private List<Entry> intersectEntries(List<Entry> entries, List<Entry> mdEntries) {
		if(mdEntries != null && entries != null) {
			mdEntries.retainAll(entries);
			return mdEntries;
		}
		if (mdEntries != null) {
			return mdEntries;
		}
		if (entries != null) {
			return entries;
		}
		return null; 
	}

	/**
	 * Evaluate a metadata query
	 * @param mdQueryString a SPARQL syntax string
	 * @return a list with entries
	 * @throws RepositoryException 
	 * @throws  
	 * @throws Exception
	 */
	private List<Entry> searchMetadataQuery(String mdQueryString) throws RepositoryException {
		if(mdQueryString == null) {
			return null;
		}
		List<Entry> entryURIs = new ArrayList<Entry>();
		RepositoryConnection rc = null;
		try {
			rc = entry.getRepository().getConnection();
			TupleQuery mdQuery = rc.prepareTupleQuery(QueryLanguage.SPARQL, mdQueryString);

			List<String> mdURIs = new ArrayList<String>();
			TupleQueryResult result = mdQuery.evaluate();

			while (result.hasNext()) {
				BindingSet set = result.next();
				Iterator<Binding> itr = set.iterator();
				while(itr.hasNext())  {
					Binding obj = itr.next();
					if(obj.getValue() instanceof org.openrdf.model.URI) {
						String mdURI = obj.getValue().toString();
						if(!mdURIs.contains(mdURI)) {
							mdURIs.add(mdURI);
						}
					}
				}
			}
 
			for (String mdStr : mdURIs) {
				URISplit split = new URISplit(mdStr, entry.repositoryManager.getRepositoryURL());
				URI entryURI = split.getMetaMetadataURI();
				if (!entryURIs.contains(entryURI) && (entryURI != null)) {
					Entry ent = null;
					try {
						ent = this.getEntry(entryURI);
					} catch (NullPointerException npe) {
						continue;
					} catch (AuthorizationException au) {
						continue;
					}
					if (ent != null && !entryURIs.contains(ent)) {
						entryURIs.add(ent);
					}
				}
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw e;
		} catch (MalformedQueryException e) {
			e.printStackTrace();
		} catch (QueryEvaluationException e) {
			e.printStackTrace();
		} finally {
			rc.close();
		}
		return entryURIs; 
	}

	/**
	 * intersection for all entries
	 * @param intersectionEntries
	 * @param entries
	 * @param mdEntries
	 * @param contextList
	 * @return
	 */
	private List<Entry> intersectEntriesFromContexts(List<Entry> intersectionEntries, List<URI> contextList) {
		if(contextList == null) {
			return intersectionEntries; 
		}

		// filter the list so that only entrys wich belong to one of the specified contexts are included
		List<Entry> resultList = new ArrayList<Entry>();
		if (intersectionEntries != null) {
			for (URI u: contextList) {
				for (Entry e: intersectionEntries) {
					if(u.equals(e.getContext().getURI())) {
						resultList.add(e);
					}
				}
			}
		}

		return resultList; 
	}

	/**
	 * Evaluates the entry SPARQL query
	 * @param entryQueryString a string with a SPARQL query.
	 * @return list with entries or null.
	 * @throws RepositoryException 
	 * @throws Exception if error
	 */
	private List<Entry> searchEntryQuery(String entryQueryString) throws RepositoryException {
		TupleQuery entryQuery; 
		 
		if (entryQueryString == null) {
			return null; 
		}
		
		List<Entry> entries = new ArrayList<Entry>();
		RepositoryConnection rc = null;
		try {
			rc = entry.getRepository().getConnection();
			entryQuery = rc.prepareTupleQuery(QueryLanguage.SPARQL, entryQueryString); 
			TupleQueryResult result = entryQuery.evaluate(); 

			while(result.hasNext()) {
				BindingSet set = result.next(); 
				Iterator<Binding> itr = set.iterator(); 	 
				while(itr.hasNext())  {
					Binding obj = itr.next(); 
					if(obj.getValue() instanceof org.openrdf.model.URI) {
						Entry entry = this.getEntry(new URI(obj.getValue().toString())); 
						if (!entries.contains(entry))
							entries.add(entry);
					}
				}
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
			throw e;
		} catch (MalformedQueryException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			e.printStackTrace();
		} catch (QueryEvaluationException e) {
			e.printStackTrace();
		} finally {
			rc.close();
		}
		return entries;
	}

	// TODO: 
	public List<Entry> search(String pattern, List<URI> list) {
		return null; 
	}

	public void initializeSystemEntries() {
		super.initializeSystemEntries();

		// TODO Auto-generated method stub
		RepositoryManager repMan = entry.repositoryManager;
		String base = repMan.getRepositoryURL().toString();
		for (String id: repMan.getSystemContextAliases()) {
			Entry scon = this.getByEntryURI(URISplit.fabricateURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID,
					RepositoryProperties.ENTRY_PATH, id));
			if (scon == null) {
				scon = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.SystemContext, null, id);
				setMetadata(scon, id, null);
			}
//			repMan.setSystemContext(scon.getId(), scon);			
			Context sc = (Context) scon.getResource();
			if (this.getContextAlias(scon.getResourceURI()) == null) {
				this.setContextAlias(scon.getResourceURI(), id);			
			}

			if (sc != this) {
				sc.initializeSystemEntries();
			}
			addSystemEntryToSystemEntries(scon.getEntryURI());
		}
		//Special case, metadata cannot be set on initialization before PrincipalManager is available.
		if (systemEntriesEntry.getLocalMetadata().getGraph().isEmpty()) {
			setMetadata(systemEntriesEntry, "System entries", null);
		}

		for (String id: repMan.getSystemContextAliases()) {
			Entry scon = this.getByEntryURI(URISplit.fabricateURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID,
					RepositoryProperties.ENTRY_PATH, id));
			if (scon.getAllowedPrincipalsFor(AccessProperty.ReadMetadata).isEmpty()) {
				scon.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, repMan.getPrincipalManager().getGuestUser().getURI());
			}
		}
		
		allContexts = (EntryImpl) get("_all");
		if (allContexts == null) {
			allContexts = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_all");
			setMetadata(allContexts, "all contexts", "This is a list of all contexts in the ContextManager.");
			allContexts.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, repMan.getPrincipalManager().getGuestUser().getURI());
			allContexts.addAllowedPrincipalsFor(AccessProperty.ReadResource, repMan.getPrincipalManager().getGuestUser().getURI());
			log.info("Successfully added the _all contexts list");
		}
		EntryImpl e = (EntryImpl) allContexts;
		e.setResource(new SystemList(e, e.getSesameResourceURI()) {
			@Override
			public List<URI> getChildren() {
				Iterator<URI> entryIterator = getEntries().iterator();
				List<URI> contextUris = new ArrayList<URI>();

				//sort out the users
				while(entryIterator.hasNext()) {
					URI nextURI = entryIterator.next();
					
					Entry nextEntry = getByEntryURI(nextURI);
					GraphType bt = nextEntry.getGraphType();
					if(bt == GraphType.Context || bt == GraphType.SystemContext) {
						contextUris.add(nextEntry.getEntryURI());
					}
				}

				return contextUris;
			}
		});
		addSystemEntryToSystemEntries(allContexts.getEntryURI());
		
		Entry top = get("_top");
		if(top == null) {
			top = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.List, null, "_top");
			setMetadata(top, "Top folder", null);
			log.info("Successfully added the top list");
		}
		addSystemEntryToSystemEntries(top.getEntryURI());
		
		Entry backup = get("_backup");
		if (backup == null) {
			backup = this.createNewMinimalItem(null, null, EntryType.Local, GraphType.None, null, RepositoryProperties.BACKUP_ID);
			setMetadata(backup, "Backup entry", "Holds information for the backup scheduler");
			log.info("Successfully added _backup entry: " + backup.getEntryURI());
		}
		addSystemEntryToSystemEntries(backup.getEntryURI());
	}
	
	
}