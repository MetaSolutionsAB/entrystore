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

package se.kmr.scam.repository.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Context;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.RepositoryException;
import se.kmr.scam.repository.RepositoryManager;
import se.kmr.scam.repository.RepositoryProperties;
import se.kmr.scam.repository.User;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.test.TestSuite;

public class UserImpl extends RDFResource implements User {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(UserImpl.class);

	private String secret;

	private String language;

	private java.net.URI homeContext;
	
	private RepositoryManager rm;
	
	/**
	 * Creates a new user
	 * @param entry
	 * @param resourceURI
	 * @param cache
	 */
	//What to do with the cache?
	protected UserImpl(EntryImpl entry, URI resourceURI, SoftCache cache) {
		super(entry, resourceURI);
		rm = entry.getRepositoryManager();
	}

	/**
	 * Returns the name of the user
	 * @return the name of the user
	 */
	public String getName() {
		//No access control check since everyone should have access to this information.
		return ((PrincipalManager) this.entry.getContext()).getPrincipalName(this.getURI());
	}

	/**
	 * Tries to sets the users name.
	 * @param newName the requested name
	 * @return true if the name was approved, false otherwise
	 */
	public boolean setName(String newName) {
		return ((PrincipalManager) this.entry.getContext()).setPrincipalName(this.getURI(), newName);
	}

	/**
	 * Returns the secret of the user
	 * @return the secret of the user
	 */
	public String getSecret() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);

		if (this.secret == null) {
			// we allow an override of the admin password in the config file
			if (rm.getPrincipalManager().getAdminUser().getEntry().getId().equals(entry.getId())) {
				if (rm.getConfiguration().containsKey(Settings.SCAM_AUTH_ADMIN_SECRET)) {
					log.warn("Admin secret override in config file");
					this.secret = rm.getConfiguration().getString(Settings.SCAM_AUTH_ADMIN_SECRET);
					return this.secret;
				}
			}

			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.secret, null,false, resourceURI).asList();
				if (!matches.isEmpty()) {
					this.secret = matches.get(0).getObject().stringValue();
				}
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (org.openrdf.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}

		return this.secret;
	}

	/**
	 * Tries to sets the users secret (password).
	 * @param secret the requested secret
	 * @param pm The PrincipalManager that contains this principal
	 * @return true if the secret was approved, false otherwise
	 */
	public boolean setSecret(String secret) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		if(!entry.getRepositoryManager().getPrincipalManager().isValidSecret(secret)) {
			return false;
		}

		try {
			RepositoryConnection rc = this.entry.repository.getConnection();
			ValueFactory vf = this.entry.repository.getValueFactory();
			rc.setAutoCommit(false);
			try {
				synchronized (this) {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.secret, null,false, resourceURI), resourceURI);
					rc.add(resourceURI,RepositoryProperties.secret, vf.createLiteral(secret), resourceURI);
					rc.commit();
				}
				return true;
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				rc.rollback();
			} finally {
				rc.close();
				this.secret = secret;
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to Repository.", e);
		}
		return false;
	}

	public void setMetadata(Entry entry, String title, String desc) {
		try {
			Graph graph = entry.getLocalMetadata().getGraph();
			ValueFactory vf = graph.getValueFactory(); 
			org.openrdf.model.URI root = vf.createURI(entry.getResourceURI().toString());
			graph.add(root, TestSuite.dc_title, vf.createLiteral(title, "en"));
			if (desc != null) {
				graph.add(root, TestSuite.dc_description, vf.createLiteral(desc, "en"));
			}
			entry.getLocalMetadata().setGraph(graph);
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
	}
	
	public Context getHomeContext() {
		//No access control check since everyone should have access to this information.
		if (this.homeContext == null) {
			RepositoryConnection rc = null;
			
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null,false, resourceURI).asList();
				if (!matches.isEmpty()) {
					this.homeContext = java.net.URI.create(matches.get(0).getObject().stringValue());
				}
				
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (org.openrdf.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		
		if (this.homeContext != null) {
			Entry eContext = this.entry.getRepositoryManager().getContextManager().getByEntryURI(this.homeContext);
			if (eContext != null) {
				return (Context) eContext.getResource();
			}
		}

		return null;
	}

	public boolean setHomeContext(Context context) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		try {
			RepositoryConnection rc = this.entry.repository.getConnection();
			rc.setAutoCommit(false);
			try {
				synchronized (this) {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.homeContext, null,false, resourceURI), resourceURI);
					rc.add(resourceURI,RepositoryProperties.homeContext, ((EntryImpl) context.getEntry()).getSesameEntryURI(), resourceURI);
					rc.commit();
				}
				return true;
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				rc.rollback();
			} finally {
				rc.close();
				this.homeContext = context.getEntry().getEntryURI();
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to Repository.", e);
		}
		return false;
	}

	public String getLanguage() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		if (this.language == null) {
			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.language, null, false, resourceURI).asList();
				if (!matches.isEmpty()) {
					this.language = matches.get(0).getObject().stringValue();
				}
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (org.openrdf.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		return this.language;
	}

	public boolean setLanguage(String language) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		
		try {
			RepositoryConnection rc = this.entry.repository.getConnection();
			ValueFactory vf = this.entry.repository.getValueFactory();
			rc.setAutoCommit(false);
			try {
				synchronized (this) {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.language, null, false, resourceURI), resourceURI);
					rc.add(resourceURI, RepositoryProperties.language, vf.createLiteral(language), resourceURI);
					rc.commit();
				}
				return true;
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				rc.rollback();
			} finally {
				rc.close();
				this.language = language;
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to Repository.", e);
		}
		return false;
	}

	public Set<java.net.URI> getGroups() {
		HashSet<java.net.URI> set = new HashSet<java.net.URI>();
		List<Statement> relations = this.entry.getRelations();
		for (Statement statement : relations) {
			if (statement.getPredicate().equals(RepositoryProperties.hasGroupMember)) {
				set.add(java.net.URI.create(statement.getSubject().toString()));
			}
		}
		return set;
	}

}