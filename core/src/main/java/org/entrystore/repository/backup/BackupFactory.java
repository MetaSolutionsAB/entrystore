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

package org.entrystore.repository.backup;


import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;

import org.entrystore.repository.Entry;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.URISplit;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Backup Factory.
 * 
 * @author Hannes Ebner
 */
public class BackupFactory {
	
	static Logger log = LoggerFactory.getLogger(BackupFactory.class);
	
	private RepositoryManagerImpl rm;

	public BackupFactory(RepositoryManagerImpl rm) {
		this.rm = rm;
	}
	
	public URI getBackupEntryURI() {
		String base = rm.getRepositoryURL().toString();
		return URISplit.fabricateURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, RepositoryProperties.BACKUP_ID);
	}
	
	public BackupScheduler createBackupScheduler(String timeRegExp, boolean gzip, boolean maintenance, int upperLimit, int lowerLimit, int expiresAfterDays) {
		// The following check is handled during startup in the constructor of ScamApplication
//		String backupStatus = rm.getConfiguration().getString(Settings.SCAM_BACKUP_SCHEDULER, "off");
//		if ("off".equals(backupStatus.trim())) {
//			log.warn("Backup is disabled in configuration");
//			return null;
//		}
		
		// Put new RDF in the context graph ; 
		Entry contextEntry = rm.getContextManager().getEntry(getBackupEntryURI());
		if (contextEntry == null) {
			log.error("Can not find the backup context entry");
			return null;
		}
		
		initBackupEntry(contextEntry, timeRegExp, gzip, maintenance, upperLimit, lowerLimit, expiresAfterDays);
		
		return new BackupScheduler(getBackupEntryURI(), rm, timeRegExp, gzip, maintenance, upperLimit, lowerLimit, expiresAfterDays); 
	}

	public BackupScheduler getBackupScheduler() {
		// The following check is handled during startup in the constructor of ScamApplication
//		String backupStatus = rm.getConfiguration().getString(Settings.SCAM_BACKUP_SCHEDULER, "off");
//		if ("off".equals(backupStatus.trim())) {
//			log.warn("Backup is disabled in configuration");
//			return null;
//		}

		String timeRegExp = null;
		boolean gzip = false;
		boolean maintenance = false;
		int upperLimit = -1;
		int lowerLimit = -1;
		int expiresAfterDays = -1;

		Entry backupEntry = rm.getContextManager().getEntry(getBackupEntryURI()); 

		if (isBackupEntry(backupEntry)) {
			for (Statement s : backupEntry.getGraph()) {
				if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "timeRegularExpression")) {
					timeRegExp = s.getObject().stringValue();
				} else if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "gzip")) {
					gzip = Boolean.parseBoolean(s.getObject().stringValue());
				} else if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "maintenance")) {
					maintenance = Boolean.parseBoolean(s.getObject().stringValue());
				} else if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "upperLimit")) {
					upperLimit = Integer.parseInt(s.getObject().stringValue());
				} else if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "lowerLimit")) {
					lowerLimit = Integer.parseInt(s.getObject().stringValue());
				} else if (s.getPredicate().toString().equals(RepositoryProperties.NSbase + "expiresAfterDays")) {
					expiresAfterDays = Integer.parseInt(s.getObject().stringValue());
				}
			}
		} else {
			return null;
		}

		return new BackupScheduler(getBackupEntryURI(), rm, timeRegExp, gzip, maintenance, upperLimit, lowerLimit, expiresAfterDays);
	}

	private void initBackupEntry(Entry backupEntry, String timeRegExp, boolean gzip, boolean maintenance, int upperLimit, int lowerLimit, int expiresAfterDays) {
		Graph graph = backupEntry.getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI root = vf.createURI(backupEntry.getEntryURI().toString());
		try {
			org.openrdf.model.URI backupRoot = vf.createURI(RepositoryProperties.NSbase + "Backup"); 
			graph.add(root, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "backup") , backupRoot);
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "timeRegularExpression"), vf.createLiteral(timeRegExp));
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "gzip"), vf.createLiteral(gzip));
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "maintenance"), vf.createLiteral(maintenance));
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "upperLimit"), vf.createLiteral(upperLimit));
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "lowerLimit"), vf.createLiteral(lowerLimit));
			graph.add(backupRoot, new org.openrdf.model.impl.URIImpl(RepositoryProperties.NSbase + "expiresAfterDays"), vf.createLiteral(expiresAfterDays));
			backupEntry.setGraph(graph);
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			e.printStackTrace();
		}
	}
	
	public void deleteBackupInformation(Entry backupEntry) {
		Graph graph = backupEntry.getGraph();
		ValueFactory vf = graph.getValueFactory();
		try {
			org.openrdf.model.URI root = vf.createURI(RepositoryProperties.NSbase + "backup");
			org.openrdf.model.URI backupRoot = vf.createURI(RepositoryProperties.NSbase + "Backup");
			Collection<Statement> statements = new ArrayList<Statement>();
			for (Statement statement : graph) {
				if (statement.getPredicate().equals(root) || statement.getSubject().equals(backupRoot)) {
					statements.add(statement);
				}
			}
			graph.removeAll(statements);
			backupEntry.setGraph(graph);
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			e.printStackTrace();
		}
	}
	
	public boolean isBackupEntry(Entry entry) {
		for (Statement s : entry.getGraph()) {
			if ((s.getPredicate().toString().equals(RepositoryProperties.NSbase + "backup")) &&
					(s.getObject().stringValue().equals(RepositoryProperties.NSbase + "Backup"))) {
					return true;
				}
		}
		return false;
	}

}