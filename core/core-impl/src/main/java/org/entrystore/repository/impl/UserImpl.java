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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.entrystore.repository.Context;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.User;
import org.entrystore.repository.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.test.TestSuite;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class UserImpl extends RDFResource implements User {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(UserImpl.class);

	private String saltedHashedSecret;

	private String language;

	private java.net.URI homeContext;
	
	private String externalID;
	
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
	@Deprecated
	public String getSecret() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);

		String secret = null;
		RepositoryConnection rc = null;
		try {
			rc = this.entry.repository.getConnection();
			List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.secret, null, false, resourceURI).asList();
			if (!matches.isEmpty()) {
				secret = matches.get(0).getObject().stringValue();
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
		} finally {
			try {
				rc.close();
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
			}
		}

		return secret;
	}
	
	public String getSaltedHashedSecret() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);

		if (this.saltedHashedSecret == null) {
			// we allow an override of the admin password in the config file
			if (rm.getPrincipalManager().getAdminUser().getEntry().getId().equals(entry.getId())) {
				if (rm.getConfiguration().containsKey(Settings.AUTH_ADMIN_SECRET)) {
					log.warn("Admin secret override in config file");
					this.saltedHashedSecret = Password.getSaltedHash(rm.getConfiguration().getString(Settings.AUTH_ADMIN_SECRET));
					return this.saltedHashedSecret;
				}
			}

			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.saltedHashedSecret, null, false, resourceURI).asList();
				if (!matches.isEmpty()) {
					this.saltedHashedSecret = matches.get(0).getObject().stringValue();
				}
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to repository", e);
			} finally {
				try {
					rc.close();
				} catch (org.openrdf.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}

		return this.saltedHashedSecret;
	}
	
	/**
	 * Sets the user's password. Does not store the password in clear-text, it is salted and hashed.
	 * 
	 * @param secret The new password
	 * @return True if the password was approved and successfully set, false otherwise
	 */
	public boolean setSecret(String secret) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		if (!entry.getRepositoryManager().getPrincipalManager().isValidSecret(secret)) {
			return false;
		}
		
		String shSecret = Password.getSaltedHash(secret);
		if (shSecret ==  null) {
			return false;
		}

		try {
			RepositoryConnection rc = this.entry.repository.getConnection();
			ValueFactory vf = this.entry.repository.getValueFactory();
			rc.setAutoCommit(false);
			try {
				synchronized (this) {
					// remove an eventually existing plaintext password and store only a salted hash
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.secret, null, false, resourceURI), resourceURI);
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.saltedHashedSecret, null, false, resourceURI), resourceURI);
					rc.add(resourceURI, RepositoryProperties.saltedHashedSecret, vf.createLiteral(shSecret), resourceURI);
					rc.commit();
				}
				this.saltedHashedSecret = shSecret;
				return true;
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				rc.rollback();
			} finally {
				rc.close();
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
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
					if (language != null) {
						rc.add(resourceURI, RepositoryProperties.language, vf.createLiteral(language), resourceURI);
					}
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
	
	/**
	 * @return An E-Mail address that can be mapped to an external authentication service, e.g. OpenID.
	 * 
	 * @see org.entrystore.repository.User#getExternalID()
	 */
	public String getExternalID() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		if (externalID == null) {
			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.externalID, null, false, resourceURI).asList();
				if (!matches.isEmpty()) {
					externalID = matches.get(0).getObject().stringValue();
					String prefix = "mailto:";
					if (externalID.contains(prefix)) {
						externalID = externalID.substring(externalID.lastIndexOf(prefix) + prefix.length());
					}
				}
			} catch (org.openrdf.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to repository", e);
			} finally {
				try {
					rc.close();
				} catch (org.openrdf.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		return this.externalID;
	}

	/**
	 * @param eid External ID, expects an E-Mail address.
	 * 
	 * @see org.entrystore.repository.User#setExternalID(java.lang.String)
	 */
	public boolean setExternalID(String eid) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		
		try {
			RepositoryConnection rc = this.entry.repository.getConnection();
			ValueFactory vf = this.entry.repository.getValueFactory();
			rc.setAutoCommit(false);
			try {
				synchronized (this) {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.externalID, null, false, resourceURI), resourceURI);
					if (eid != null) {
						rc.add(resourceURI, RepositoryProperties.externalID, vf.createURI("mailto:", eid), resourceURI);
					}
					rc.commit();
				}
				return true;
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				rc.rollback();
			} finally {
				rc.close();
				this.externalID = eid;
			}
		} catch (org.openrdf.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
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