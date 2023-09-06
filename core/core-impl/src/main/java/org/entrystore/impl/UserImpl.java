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

package org.entrystore.impl;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.repository.util.NS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class UserImpl extends RDFResource implements User {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(UserImpl.class);

	private String saltedHashedSecret;

	private String language;

	private URI homeContext;
	
	private String externalID;
	
	private RepositoryManager rm;

	public static final IRI customProperty;

	public static final IRI customPropertyKey;

	public static final IRI customPropertyValue;

	static {
		ValueFactory vf = SimpleValueFactory.getInstance();
		customProperty = vf.createIRI(NS.entrystore, "customProperty");
		customPropertyKey = vf.createIRI(NS.entrystore, "customPropertyKey");
		customPropertyValue = vf.createIRI(NS.entrystore, "customPropertyValue");
	}

	/**
	 * Creates a new user
	 * @param entry
	 * @param resourceURI
	 * @param cache
	 */
	//What to do with the cache?
	protected UserImpl(EntryImpl entry, IRI resourceURI, SoftCache cache) {
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
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
		} finally {
			try {
				rc.close();
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
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
					try {
						this.saltedHashedSecret = Password.getSaltedHash(rm.getConfiguration().getString(Settings.AUTH_ADMIN_SECRET));
						return this.saltedHashedSecret;
					} catch (IllegalArgumentException iae) {
						log.error("Admin secret override was not successful due to password rule violation");
					}
				}
			}

			RepositoryConnection rc = null;
			try {
				rc = this.entry.repository.getConnection();
				List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.saltedHashedSecret, null, false, resourceURI).asList();
				if (!matches.isEmpty()) {
					this.saltedHashedSecret = matches.get(0).getObject().stringValue();
				}
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to repository", e);
			} finally {
				try {
					rc.close();
				} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
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

		String shSecret = null;
		try {
			shSecret = Password.getSaltedHash(secret);
		} catch (IllegalArgumentException iae) {
			log.info(iae.getMessage());
			return false;
		}

		return setSaltedHashedSecret(shSecret);
	}

	/**
	 * Sets the user's hashed password.
	 *
	 * @param shSecret The new salted and hashed password
	 * @return True if the was successfully set, false otherwise
	 */
	public boolean setSaltedHashedSecret(String shSecret) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				rc.begin();
				try {
					// remove an eventually existing plaintext password and store only a salted hash
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.secret, null, false, resourceURI), resourceURI);
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.saltedHashedSecret, null, false, resourceURI), resourceURI);
					rc.add(resourceURI, RepositoryProperties.saltedHashedSecret, vf.createLiteral(shSecret), resourceURI);
					this.entry.updateModifiedDateSynchronized(rc, vf);
					rc.commit();
					this.saltedHashedSecret = shSecret;
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					rc.close();
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
		}

		return false;
	}

	public void setMetadata(Entry entry, String title, String desc) {
		try {
			Model graph = entry.getLocalMetadata().getGraph();
			ValueFactory vf = SimpleValueFactory.getInstance();
			IRI root = vf.createIRI(entry.getResourceURI().toString());
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
                List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null,false, entry.getSesameEntryURI()).asList();
                if (!matches.isEmpty()) {
                    this.homeContext = URI.create(matches.get(0).getObject().stringValue());
                } else { //TODO else case is backwards compatible code, remove in future.
                    matches = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null, false, resourceURI).asList();
                    if (!matches.isEmpty()) {
                        this.homeContext = URI.create(matches.get(0).getObject().stringValue());
                    }
                }
				
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
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
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				rc.begin();
				try {
					//Remove homecontext and remove inverse relation cache.
					RepositoryResult<Statement> iter = rc.getStatements(resourceURI, RepositoryProperties.homeContext, null, false, entry.getSesameEntryURI());
					while (iter.hasNext()) {
						Statement statement = iter.next();
						URI sourceEntryURI = URI.create(statement.getObject().stringValue());
						EntryImpl sourceEntry = (EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(sourceEntryURI);
						if (sourceEntry != null) {
							sourceEntry.removeRelationSynchronized(statement, rc, vf);
						}
						rc.remove(statement, entry.getSesameEntryURI());
					}
					iter.close();

					//TODO Remove the following line in future as it corresponds to backward compatability where homecontext where saved in resource graph instead of entry graph.
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.homeContext, null, false, resourceURI), resourceURI);

					//Add new homecontext and add inverse relational cache
					if (context != null) {
						Statement newStatement = vf.createStatement(resourceURI, RepositoryProperties.homeContext, ((EntryImpl) context.getEntry()).getSesameEntryURI(), entry.getSesameEntryURI());
						rc.add(newStatement);
						((EntryImpl) context.getEntry()).addRelationSynchronized(newStatement, rc, this.entry.repository.getValueFactory());
					}
					this.entry.updateModifiedDateSynchronized(rc, vf);
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					rc.close();
					//We poke in the internals of entryImpl, to notify that it has relations for later setGraph calls to work
					entry.invRelations = true;
					this.homeContext = context.getEntry().getEntryURI();
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository.", e);
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
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to Repository.", e);
			} finally {
				try {
					rc.close();
				} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		return this.language;
	}

	public boolean setLanguage(String language) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				rc.begin();
				try {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.language, null, false, resourceURI), resourceURI);
					if (language != null) {
						rc.add(resourceURI, RepositoryProperties.language, vf.createLiteral(language), resourceURI);
					}
					this.entry.updateModifiedDateSynchronized(rc, vf);
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					rc.close();
					this.language = language;
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to Repository.", e);
		}
		return false;
	}
	
	/**
	 * @return An E-Mail address that can be mapped to an external authentication service, e.g. OpenID.
	 * 
	 * @see org.entrystore.User#getExternalID()
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
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage(), e);
				throw new RepositoryException("Failed to connect to repository", e);
			} finally {
				try {
					rc.close();
				} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
		return this.externalID;
	}

	/**
	 * @param eid External ID, expects an E-Mail address.
	 * 
	 * @see org.entrystore.User#setExternalID(java.lang.String)
	 */
	public boolean setExternalID(String eid) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				rc.begin();
				try {
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.externalID, null, false, resourceURI), resourceURI);
					if (eid != null) {
						rc.add(resourceURI, RepositoryProperties.externalID, vf.createIRI("mailto:", eid), resourceURI);
						this.entry.updateModifiedDateSynchronized(rc, vf);
					}
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					rc.close();
					this.externalID = eid;
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
		}
		return false;
	}

	public Set<URI> getGroups() {
		HashSet<URI> set = new HashSet<URI>();
		List<Statement> relations = this.entry.getRelations();
		for (Statement statement : relations) {
			if (statement.getPredicate().equals(RepositoryProperties.hasGroupMember)) {
				set.add(URI.create(statement.getSubject().toString()));
			}
		}
		return set;
	}

	public Map<String, String> getCustomProperties() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);

		Map<String, String> result = new HashMap<>();
		Model userResourceGraph = getGraph();
		for (Statement s : userResourceGraph.filter(resourceURI, customProperty, null)) {
			if (s.getObject() instanceof BNode) {
				String keyStr = null;
				String valueStr = null;
				Iterator<Statement> argKeyIt = userResourceGraph.filter((BNode) s.getObject(), customPropertyKey, null).iterator();
				if (argKeyIt.hasNext()) {
					Value argKey = argKeyIt.next().getObject();
					if (argKey instanceof Literal) {
						keyStr = ((Literal) argKey).stringValue();
						Iterator<Statement> argValueIt = userResourceGraph.filter((BNode) s.getObject(), customPropertyValue, null).iterator();
						if (argValueIt.hasNext()) {
							Value argValue = argValueIt.next().getObject();
							if (argValue instanceof Literal) {
								valueStr = ((Literal) argValue).stringValue();
							}
						}
					}
				}
				if (keyStr != null && valueStr != null) {
					result.put(keyStr.toLowerCase(), valueStr);
				}
			}
		}

		return result;
	}

	public boolean setCustomProperties(Map<String, String> properties) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		if (properties == null) {
			throw new IllegalArgumentException("Parameter must not be null");
		}

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				rc.begin();
				try {
					rc.remove(rc.getStatements(null, customProperty, null, false, resourceURI), resourceURI);
					rc.remove(rc.getStatements(null, customPropertyKey, null, false, resourceURI), resourceURI);
					rc.remove(rc.getStatements(null, customPropertyValue, null, false, resourceURI), resourceURI);

					for (java.util.Map.Entry<String, String> e : properties.entrySet()) {
						BNode bnode = vf.createBNode();
						rc.add(resourceURI, customProperty, bnode, resourceURI);
						rc.add(bnode, customPropertyKey, vf.createLiteral(e.getKey()), resourceURI);
						rc.add(bnode, customPropertyValue, vf.createLiteral(e.getValue()), resourceURI);
					}

					this.entry.updateModifiedDateSynchronized(rc, vf);

					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					return true;
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					rc.close();
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new RepositoryException("Failed to connect to repository", e);
		}
		return false;
	}

	public boolean isDisabled() {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		RepositoryConnection rc = null;
		try {
			rc = this.entry.repository.getConnection();
			List<Statement> matches = rc.getStatements(resourceURI, RepositoryProperties.disabled, null, false, resourceURI).asList();
			if (!matches.isEmpty()) {
				Literal l = (Literal) matches.get(0).getObject();
				return l.booleanValue();
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage());
			throw new RepositoryException("Failed to connect to repository", e);
		} finally {
			try {
				if (rc != null) {
					rc.close();
				}
			} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return false;
	}

	public void setDisabled(boolean disabled) {
		rm.getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				ValueFactory vf = this.entry.repository.getValueFactory();
				try {
					rc.begin();
					rc.remove(rc.getStatements(resourceURI, RepositoryProperties.disabled, null, false, resourceURI), resourceURI);
					if (disabled) {
						rc.add(resourceURI, RepositoryProperties.disabled, vf.createLiteral(disabled), resourceURI);
					}
					this.entry.updateModifiedDateSynchronized(rc, vf);
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					rc.rollback();
				} finally {
					if (rc != null) {
						rc.close();
					}
				}
			}
		} catch (org.eclipse.rdf4j.repository.RepositoryException e) {
			log.error(e.getMessage());
			throw new RepositoryException("Failed to connect to repository", e);
		}
	}

}