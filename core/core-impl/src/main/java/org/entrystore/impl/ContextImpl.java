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

import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.DeletedEntryInfo;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Quota;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.security.DisallowedException;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.URISplit;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;


public class ContextImpl extends ResourceImpl implements Context {

	private long counter = -1;
	protected SoftCache cache;
	protected String id;
	protected HashMap<URI, Object> extMdUri2entry;
	protected HashMap<URI, Object> res2entry;
	protected ArrayList<URI> systemEntries = new ArrayList<URI>();
	private static Logger log = LoggerFactory.getLogger(ContextImpl.class);

	public static final org.openrdf.model.URI DCModified;
	public static final org.openrdf.model.URI DCTermsModified;

	private static final DateFormat DATE_PARSER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
	
	private Object quotaMutex = new Object();
	protected long quotaFillLevel = Quota.VALUE_UNCACHED;
	protected long quota = Quota.VALUE_UNCACHED;

	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		DCModified = vf.createURI(NS.dc, "modified");
		DCTermsModified = vf.createURI(NS.dcterms, "modified");
	}

	protected ContextImpl(EntryImpl entry, String uri, SoftCache cache) {
		super(entry, uri);
		this.cache = cache;
		this.id = uri.substring(uri.lastIndexOf('/')+1);
	}

	public ContextImpl(EntryImpl entry, org.openrdf.model.URI contextUri, SoftCache cache)  {
		super(entry, contextUri);
		this.cache = cache;
		this.id = resourceURI.toString().substring(resourceURI.toString().lastIndexOf('/') + 1);
	}

	public SoftCache getCache() {
		return this.cache;
	}

	/**
	 * This method recreates an index by in part inspecting URIs of Sesame contexts.
	 */
	public void reIndex() {
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.setAutoCommit(false);

					// delete old index
					rc.remove((Resource) null, RepositoryProperties.mdHasEntry, null, this.resourceURI);
					rc.remove((Resource) null, RepositoryProperties.resHasEntry, null, this.resourceURI);
					rc.remove((Resource) null, RepositoryProperties.counter, null, this.resourceURI);
					
					List<Statement> stmntsToAdd = new ArrayList<Statement>();

					// create new index by finding all sesame contexts which belong to this SCAM context
					int maxIndex = 0;
					RepositoryResult<Statement> resources = rc.getStatements(null, RepositoryProperties.resource, null, false);
					while (resources.hasNext()) {
						Statement statement = resources.next();
						Resource mmd = statement.getContext();
						if (mmd instanceof org.openrdf.model.URI) {
							if (!mmd.stringValue().startsWith(entry.getRepositoryManager().getRepositoryURL().toString())) {
								log.warn("This Entry URI does not belong to this repository: " + mmd.stringValue());
								continue;
							}
							
							StringTokenizer stok = Util.extractParameters(entry.repositoryManager, (org.openrdf.model.URI) mmd);
							if (stok.countTokens() == 3 && stok.nextToken().equals(this.id)) { //Belongs to this context.
								try {
									stok.nextToken(); //Ignoring the M
									int index = Integer.parseInt(stok.nextToken());
									if (index > maxIndex) {
										maxIndex = index;
									}
								} catch (NumberFormatException nfe) {}
								// this does not work: addToIndex((org.openrdf.model.URI) statement.getSubject(),(org.openrdf.model.URI) statement.getObject(),(org.openrdf.model.URI) statement.getContext(), rc);
								stmntsToAdd.add(vf.createStatement((Resource) statement.getObject(), RepositoryProperties.resHasEntry, statement.getContext(), this.resourceURI));
							}
						}
					}
					
					RepositoryResult<Statement> externalMD = rc.getStatements(null, RepositoryProperties.externalMetadata, null, false);
					while (externalMD.hasNext()) {
						Statement statement = externalMD.next();
						Resource mmd = statement.getContext();
						if (mmd instanceof org.openrdf.model.URI) {
							StringTokenizer stok = Util.extractParameters(entry.repositoryManager, (org.openrdf.model.URI) mmd);
							if (stok.countTokens() == 3 && stok.nextToken().equals(this.id)) { //Belongs to this context.
								stmntsToAdd.add(vf.createStatement((Resource) statement.getObject(), RepositoryProperties.mdHasEntry, statement.getContext(), this.resourceURI));
							}
						}
					}
					
					rc.add(stmntsToAdd, this.resourceURI);
					rc.add(this.resourceURI, RepositoryProperties.counter, vf.createLiteral(maxIndex), this.resourceURI);					
					rc.commit();
				} catch (Exception e) {
					rc.rollback();
					log.error(e.getMessage());
					throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to repository", e);
		}
		
		this.res2entry = null;
		this.extMdUri2entry = null;
		
		loadIndex();
	}

	private void push(URI from, URI to, HashMap<URI, Object> map) {
		if (from == null || to == null) {
			return;
		}
		Object existingTo = map.get(from); 
		if (existingTo == null) {
			map.put(from, to);
		} else {
			if (existingTo instanceof Set) {
				((Set<URI>) existingTo).add(to);
			} else {
				HashSet<URI> set = new HashSet<URI>();
				set.add((URI) existingTo);
				set.add(to);
				map.put(from, set);
			}
		}
	}
	private void pop(URI from, URI to, HashMap<URI, Object> map) {
		if (from == null || to == null) {
			return;
		}
		Object existingTo = map.get(from); 
		if (existingTo != null) {
			if (existingTo instanceof Set) {
				((Set) existingTo).remove(to);
				if (((Set) existingTo).isEmpty()) {
					map.remove(from);
				}
			} else if (existingTo.equals(to)){
				map.remove(from);
			}
		}
	}

	protected void loadIndex() {
		try {
			synchronized (this.entry.repository) {
				if (res2entry != null) {
					return;
				}
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					res2entry = new HashMap<URI, Object>();
					extMdUri2entry = new HashMap<URI, Object>();
					List<Statement> statements = rc.getStatements(null, null, null, false, this.resourceURI).asList();
					for (Statement statement : statements) {
						try {
							org.openrdf.model.URI predicate = statement.getPredicate();
							if (predicate.equals(RepositoryProperties.mdHasEntry)) {
								URI mdURI = URI.create(statement.getSubject().toString());
								URI entryURI = URI.create(statement.getObject().toString());
								push(mdURI, entryURI, extMdUri2entry);
							} else if (predicate.equals(RepositoryProperties.resHasEntry)) {
								URI resourceURI = URI.create(statement.getSubject().toString());
								URI entryURI = URI.create(statement.getObject().toString());
								push(resourceURI, entryURI, res2entry);
							} else if (predicate.equals(RepositoryProperties.counter)) {
								this.counter = ((Literal) statement.getObject()).intValue();
							}
						} catch (Exception e) {
							log.error(e.getMessage());
						}
					}
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
	}

	private void addToIndex(org.openrdf.model.URI entryURI, org.openrdf.model.URI resURI, org.openrdf.model.URI extMdURI, RepositoryConnection rc) throws RepositoryException {		
		rc.add(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);							
		URI euri = URI.create(entryURI.toString());

		if (extMdURI!= null) {
			rc.add(extMdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			if (extMdUri2entry != null) {
				URI mdURI = URI.create(extMdURI.toString());
				push(mdURI, euri, extMdUri2entry);
			}
		}
		if (res2entry != null) {
			URI resourceURI = URI.create(resURI.toString());
			push(resourceURI, euri, res2entry);
		}
	}

	protected void removeFromIndex(EntryImpl entry, RepositoryConnection rc) throws RepositoryException {
		org.openrdf.model.URI entryURI = entry.getSesameEntryURI();
		org.openrdf.model.URI resURI = entry.getSesameResourceURI();
		org.openrdf.model.URI mdURI = entry.getSesameExternalMetadataURI();

		rc.remove(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);

		if (mdURI != null) {
			rc.remove(mdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			if (extMdUri2entry != null) {
				pop(entry.getExternalMetadataURI(), entry.getEntryURI(), extMdUri2entry);
			}
		}
		if (res2entry != null) {
			pop(entry.getResourceURI(), entry.getEntryURI(), res2entry);
		}
		
		// add deletion information to index
		ValueFactory vf = rc.getValueFactory();
		XMLGregorianCalendar deletedDate = null;
		try {
			deletedDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
		} catch (DatatypeConfigurationException e) {
			log.error(e.getMessage());
		}
		if (deletedDate != null) {
			Statement delDateStmnt = vf.createStatement(entryURI, RepositoryProperties.Deleted, vf.createLiteral(deletedDate), this.resourceURI);
			rc.add(delDateStmnt, this.resourceURI);
		}
		URI deletedBy = entry.getRepositoryManager().getPrincipalManager().getAuthenticatedUserURI();
		if (deletedBy != null) {
			Statement delByStmnt = vf.createStatement(entryURI, RepositoryProperties.DeletedBy, vf.createURI(deletedBy.toString()), this.resourceURI);
			rc.add(delByStmnt, this.resourceURI);
		}
	}
	
	public Map<URI, DeletedEntryInfo> getDeletedEntries() {
		RepositoryConnection rc = null;
		List<Statement> delDates = null;
		List<Statement> delPrincipals = null;
		
		synchronized (this.entry.repository) {
			try {
				rc = entry.getRepository().getConnection();
				delDates = rc.getStatements(null, RepositoryProperties.Deleted, null, false, this.resourceURI).asList();
				delPrincipals = rc.getStatements(null, RepositoryProperties.DeletedBy, null, false, this.resourceURI).asList();
			} catch (RepositoryException e) {
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

		Map<URI, Date> uri2date = new HashMap<URI, Date>();
		if (delDates != null) {
			for (Statement dateStmnt : delDates) {
				URI deletedEntryURI = URI.create(dateStmnt.getSubject().stringValue());
				Date deletionDate = ((Literal) dateStmnt.getObject()).calendarValue().toGregorianCalendar().getTime();
				uri2date.put(deletedEntryURI, deletionDate);
			}
		}

		Map<URI, URI> uri2principal = new HashMap<URI, URI>();
		for (Statement principalStmnt : delPrincipals) {
			URI deletedEntryURI = URI.create(principalStmnt.getSubject().stringValue());
			URI deletedBy = URI.create(principalStmnt.getObject().stringValue());
			uri2principal.put(deletedEntryURI, deletedBy);
		}

		Map<URI, DeletedEntryInfo> result = new HashMap<URI, DeletedEntryInfo>();
		for (URI delEntryURI : uri2principal.keySet()) {
			DeletedEntryInfo delEntryInfo = new DeletedEntryInfo(delEntryURI, uri2date.get(delEntryURI), uri2principal.get(delEntryURI));
			result.put(delEntryURI, delEntryInfo);
		}
		
		return result;
	}
	
	public Map<URI, DeletedEntryInfo> getDeletedEntriesInRange(Date from, Date until) {
		if (from == null && until == null) {
			return getDeletedEntries();
		}
		
		Map<URI, DeletedEntryInfo> result = new HashMap<URI, DeletedEntryInfo>();
		Map<URI, DeletedEntryInfo> allDeletedEntries = getDeletedEntries();
		for (URI delEntryURI : allDeletedEntries.keySet()) {
			boolean inRange = true;
			DeletedEntryInfo delEntryInfo = allDeletedEntries.get(delEntryURI);
			Date deletionDate = delEntryInfo.getDeletionDate();
			if (delEntryInfo != null &&	deletionDate != null) {
				if (from != null && !deletionDate.after(from)) {
					inRange = false;
				}
				if (until != null && !deletionDate.before(until)) {
					inRange = false;
				}
			}
			if (inRange) {
				result.put(delEntryURI, delEntryInfo);
			}
		}
		
		return result;
	}

	synchronized protected EntryImpl createNewMinimalItem(URI resourceURI, URI metadataURI, EntryType lType, GraphType bType, ResourceType rType, String entryId) {
		try {
			//Factory and connection.
			RepositoryConnection rc = entry.repository.getConnection();
			try {
				ValueFactory vf = entry.repository.getValueFactory();
				rc.setAutoCommit(false);

				//Find current counter
				if (counter == -1) {
					List<Statement> counters = rc.getStatements(
							this.resourceURI, 
							RepositoryProperties.counter, 
							null, 
							false, 
							this.resourceURI).asList();

					if (counters.size() > 0) {
						counter = ((Literal) counters.get(0).getObject()).intValue();
					} else {
						counter = 0; 
					}
				}

				//Find new information identity
				String base = entry.repositoryManager.getRepositoryURL().toString();
				List<Statement> infoRecord = null;
				String identity = null;
				if (entryId != null) {
					identity = entryId;
				} else {
					do {
						counter++;
						identity = Long.toString(counter);
						org.openrdf.model.URI entryUri = vf.createURI(base 
								+ this.id + "/" + RepositoryProperties.ENTRY_PATH + "/" + Long.toString(counter));
						infoRecord = rc.getStatements(null, null, null, false, entryUri).asList();
					} while (!infoRecord.isEmpty()); //keep counting if candidate is taken
				}

				//resURI - resourceURI
				org.openrdf.model.URI resURI = null;
				if (resourceURI != null) {
					String resourceURIStr = resourceURI.toString().replace("_newId", identity);
					resURI = vf.createURI(resourceURIStr);
				} else {
					if (bType == GraphType.Context ||
							bType == GraphType.SystemContext) {
						resURI = vf.createURI(URISplit.fabricateContextURI(base, identity).toString());					
					} else {
						resURI = vf.createURI(URISplit.fabricateURI(base, this.id, RepositoryProperties.getResourcePath(bType), identity).toString());
					}
				}

				EntryImpl newEntry = null;
				try {
					//Initialize a new item and new info.
					newEntry = new EntryImpl(identity, this, this.entry.repositoryManager, this.entry.getRepository());

					//Initialize a new information object.
					if (lType == EntryType.Reference || lType == EntryType.LinkReference) {
						newEntry.create(resURI, vf.createURI(metadataURI.toString()), bType, lType, rType, rc);
					} else {
						newEntry.create(resURI, null, bType, lType, rType, rc);					
					}
					initResource(newEntry);
					

					//Update index with new item.
					addToIndex(newEntry.getSesameEntryURI(), newEntry.getSesameResourceURI(), newEntry.getSesameExternalMetadataURI(), rc);

					//Update the index counter.
					List<Statement> counters = rc.getStatements(this.resourceURI, RepositoryProperties.counter, null, false, this.resourceURI).asList();
					rc.remove(counters, this.resourceURI);
					rc.add(this.resourceURI, RepositoryProperties.counter, vf.createLiteral(counter), this.resourceURI);

					if (bType != GraphType.SystemContext) {
						this.entry.updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
					}
					rc.commit();
					cache.put(newEntry);
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(newEntry, RepositoryEvent.EntryCreated));
					return newEntry;
				} catch (Exception e) {
					rc.rollback();
					if (newEntry != null) {
						newEntry.refreshFromRepository(rc);
					}
					throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
				}
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
	}

	public void initResource(EntryImpl newEntry) throws RepositoryException {
		if (newEntry.getEntryType() != EntryType.Local) {
			return;
		}

		switch (newEntry.getGraphType()) {
		case None:
		case PipelineResult:
			if (newEntry.getEntryType() == EntryType.Local) {
				//TODO check Representationtype as well.
				newEntry.setResource(new DataImpl(newEntry));
			}
			break;
		case List:
			newEntry.setResource(new ListImpl(newEntry, newEntry.getSesameResourceURI())); 
			break;
		case ResultList:
			//TODO
			break;
		case String:
			newEntry.setResource(new StringResource(newEntry, newEntry.getSesameResourceURI())); 
			break; 
		case Graph:
		case Pipeline:
			newEntry.setResource(new RDFResource(newEntry, newEntry.getSesameResourceURI()));
			break;
		default:
			//All other cases are only allowed by ContextManager or PrincipalManager. See overridden method there.			
			break;
		}
	}

	private ListImpl getList(URI listURI) {
		if (listURI != null) {
			URI listEntryURI = new URISplit(listURI, this.entry.getRepositoryManager().getRepositoryURL()).getMetaMetadataURI();
			Entry listItem = getByEntryURI(listEntryURI);
			if (listItem.getGraphType() == GraphType.List &&
					listItem.getEntryType() == EntryType.Local) {
				return (ListImpl) listItem.getResource();
			}
		}
		return null;
	}

	/**
	 * 
	 * @param secondChance typically a list to check if access is given in that list only.
	 * @param ap the kind of access requested.
	 * @return true if user is owner of current context, false otherwise.
	 * @throws AuthorizationException
	 */
	protected boolean checkAccess(Entry secondChance, AccessProperty ap) throws AuthorizationException {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm == null) {
			return true;
		}
		try {
			//			System.out.println("checkAccess: getAuthenticatedUserURI() = " + pm.getAuthenticatedUserURI());
			//			if(pm.getAuthenticatedUserURI() != null) {
			//				if(pm.getAuthenticatedUserURI().toString().equals("http://scam4.org/")) {
			//					try {throw new Exception();} catch (Exception e) {e.printStackTrace();}
			//				}
			//			}
	
			pm.checkAuthenticatedUserAuthorized(this.entry, ap);
			return true;
		} catch (AuthorizationException ae) {
			if (secondChance != null) {
				pm.checkAuthenticatedUserAuthorized(secondChance, ap);
				return false;
			} else {
				throw ae;
			}
		}
	}

	public Entry createLinkReference(String entryId, URI resourceURI, URI metadataURI, URI listURI) throws AuthorizationException {
		ListImpl list = getList(listURI);
		boolean isOwner = checkAccess(list != null ? list.entry : null, AccessProperty.WriteResource);
		synchronized (this.entry.repository) {
			EntryImpl entry = createNewMinimalItem(resourceURI, metadataURI, EntryType.LinkReference, GraphType.None, null, entryId);
			if (list != null) {
				list.addChild(entry.getEntryURI());
				copyACL(list, entry);
				if (!isOwner) {
					entry.setOriginalListSynchronized(listURI.toString());
				}
			}
			return entry;
		}
	}

	public Entry createReference(String entryId, URI resourceURI, URI metadataURI, URI listURI) {
		ListImpl list = getList(listURI);
		boolean isOwner = checkAccess(list != null ? list.entry : null, AccessProperty.WriteResource);
		synchronized (this.entry.repository) {
			EntryImpl entry = createNewMinimalItem(resourceURI, metadataURI, EntryType.Reference, GraphType.None, null, entryId);
			if (list != null) {
				list.addChild(entry.getEntryURI());
				copyACL(list, entry);
				if (!isOwner) {
					entry.setOriginalListSynchronized(listURI.toString());
				}
			}
			return entry;
		}
	}

	public Entry createLink(String entryId, URI resourceURI, URI listURI) {
		ListImpl list = getList(listURI);
		boolean isOwner = checkAccess(list != null ? list.entry : null, AccessProperty.WriteResource);
		synchronized (this.entry.repository) {
			EntryImpl entry = createNewMinimalItem(resourceURI, null, EntryType.Link, GraphType.None, null, entryId);
			if (list != null) {
				list.addChild(entry.getEntryURI());
				copyACL(list, entry);
				if (!isOwner) {
					entry.setOriginalListSynchronized(listURI.toString());
				}
			}

			return entry;			
		}
	}

	public Entry createResource(String entryId, GraphType buiType, ResourceType repType, URI listURI) {
		ListImpl list = null;
		boolean isOwner = false;

		if (listURI != null) {
			list = getList(listURI);
			isOwner = checkAccess(list != null ? list.entry : null, AccessProperty.WriteResource);
		}

		synchronized (this.entry.repository) {
			EntryImpl entry = createNewMinimalItem(null, null, EntryType.Local, buiType, repType, entryId);
			if (list != null) {
				log.info("Adding entry " + entry.getEntryURI() + " to list " + list.getURI());
				list.addChild(entry.getEntryURI());
				log.info("Copying ACL from list " + list.getURI() + " to entry " + entry.getEntryURI());
				copyACL(list, entry);
				if (!isOwner) {
					entry.setOriginalListSynchronized(listURI.toString());
				}
			}
			
			if (GraphType.Context.equals(buiType)) {
				((Context) entry.getResource()).initializeSystemEntries();
			} else if (GraphType.User.equals(buiType)) {
                entry.addAllowedPrincipalsFor(AccessProperty.WriteResource, entry.getResourceURI());
                entry.addAllowedPrincipalsFor(AccessProperty.WriteMetadata, entry.getResourceURI());
                entry.addAllowedPrincipalsFor(AccessProperty.ReadResource, ((PrincipalManager) this).getGuestUser().getURI());
                entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, ((PrincipalManager) this).getGuestUser().getURI());
			} else if (GraphType.Group.equals(buiType)) {
                entry.addAllowedPrincipalsFor(AccessProperty.ReadResource, ((PrincipalManager) this).getGuestUser().getURI());
                entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, ((PrincipalManager) this).getGuestUser().getURI());
                //TODO: not obvious that the following two are good defaults for group.
                entry.addAllowedPrincipalsFor(AccessProperty.WriteResource, ((PrincipalManager) this).getAuthenticatedUserURI());
                entry.addAllowedPrincipalsFor(AccessProperty.WriteMetadata, ((PrincipalManager) this).getAuthenticatedUserURI());
            }
			return entry;
		}
	}

	public void copyACL(org.entrystore.List fromList, Entry toEntry) {
		if (toEntry instanceof EntryImpl) {
			EntryImpl entry = (EntryImpl) toEntry;
			Set<URI> adminPrincipals = fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.Administer);
			if (toEntry.getGraphType() != GraphType.List || toEntry.getEntryType() != EntryType.Local) {
				PrincipalManager pm = toEntry.getRepositoryManager().getPrincipalManager();
				try {
					pm.checkAuthenticatedUserAuthorized(fromList.getEntry(), AccessProperty.Administer);
				} catch (AuthorizationException ae) {
					adminPrincipals.add(pm.getAuthenticatedUserURI());
				}
			}
			entry.updateAllowedPrincipalsFor(AccessProperty.Administer, adminPrincipals, false, true);
			entry.updateAllowedPrincipalsFor(AccessProperty.ReadMetadata, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.ReadMetadata), false, true);
			entry.updateAllowedPrincipalsFor(AccessProperty.ReadResource, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.ReadResource), false, true);
			entry.updateAllowedPrincipalsFor(AccessProperty.WriteMetadata, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.WriteMetadata), false, true);
			entry.updateAllowedPrincipalsFor(AccessProperty.WriteResource, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.WriteResource), false, true);
		} else {
			log.warn("copyACL(fromList, toEntry): Not setting an ACL: toEntry is not an instance of EntryImpl");
		}
	}
	
	public void copyACL(URI fromList, Entry toEntry) {
		copyACL(getList(fromList), toEntry);
	}

	public Entry get(String entryId) {
		return getByEntryURI(URISplit.fabricateURI(
				entry.getRepositoryManager().getRepositoryURL().toString(), 
				id, RepositoryProperties.ENTRY_PATH, entryId));
	}

	public Entry getByEntryURI(URI entryURI) {
		synchronized (cache) {
			Entry entry = cache.getByEntryURI(entryURI);
			if (entry != null) {
				//			checkAccess(entry, AccessProperty.ReadMetadata);
				return entry;
			}

			try {
				return getByMMdURIDirect(entryURI);
			} catch (RepositoryException e) {
				log.error(e.getMessage(), e);
			}
			return null;
		}
	}

	private Entry getByMMdURIDirect(URI entryURI) throws RepositoryException {
		RepositoryConnection rc = null;
		Entry result = null;
		try {
			rc = this.entry.getRepository().getConnection();
			result = getByMMdURIDirect(entryURI, rc);
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		} finally {
			rc.close();
		}
		return result;
	}

	private Entry getByMMdURIDirect(URI entryURI, RepositoryConnection rc) throws RepositoryException {
		if (entryURI == null) {
			return null;
		}
		EntryImpl newEntry = null;
		try {
			URISplit split = new URISplit(entryURI, this.entry.getRepositoryManager().getRepositoryURL());
			if (split == null || !this.id.equals(split.getContextID())) {
				return null;
			}
			newEntry = new EntryImpl(split.getID(), this, this.entry.repositoryManager, this.entry.getRepository());
			if (newEntry.load(rc)) {				
				if(newEntry.getEntryType() == EntryType.Local) {
					initResource(newEntry);
				}
				cache.put(newEntry);
				if (GraphType.Context.equals(newEntry.getGraphType()) &&
						EntryType.Local.equals(newEntry.getEntryType())) {
					org.entrystore.Resource resource = newEntry.getResource();
					if (resource != null) {
						((Context) resource).initializeSystemEntries();
					} else {
						log.error("Entry's resource is null: " + newEntry.getEntryURI());
					}
				}

				//				checkAccess(newEntry, AccessProperty.ReadMetadata);			
			} else {
				newEntry = null;
			}
		} catch (AuthorizationException ae) {
			throw ae;
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
		}
		return newEntry;
	}

	public Set<Entry> getByExternalMdURI(URI metadataURI) {
		if (extMdUri2entry == null) {
			loadIndex();
		}
		HashSet<Entry> entries = new HashSet<Entry>();
		Object value = extMdUri2entry.get(metadataURI);
		if (value != null) {
			if (value instanceof URI) {
				entries.add(getByEntryURI((URI) value));
			} else {
				Set<URI> mmdURIs = (Set<URI>) value;
				for (URI uri : mmdURIs) {
					entries.add(getByEntryURI(uri));
				}
			}
		}
		return entries;
	}

	public Set<Entry> getByResourceURI(URI resourceURI) {
		if (res2entry == null) {
			loadIndex();
		}
		HashSet<Entry> entries = new HashSet<Entry>();
		Object value = res2entry.get(resourceURI);
		if (value != null) {
			if (value instanceof URI) {
				entries.add(getByEntryURI((URI) value));
			} else {
				Set<URI> mmdURIs = (Set<URI>) value;
				for (URI uri : mmdURIs) {
					entries.add(getByEntryURI(uri));
				}
			}
		}
		return entries;
	}

	public Set<URI> getEntries() {
		//Listing entries should always be allowed?
		//Seeing metadata for each of the entries is determined in the normal way.
		//		checkAccess(null, AccessProperty.ReadResource);

		if (res2entry == null) {
			loadIndex();
		}

		Set<URI> entries = new HashSet<URI>();		
		Collection<Object> val = res2entry.values();
		for (Object object : val) {
			if (object instanceof URI) {
				entries.add((URI)object);
			} else {
				entries.addAll((Collection<URI>)object);
			}
		}

		return entries;
	}

	public Set<URI> getResources() {
		checkAccess(null, AccessProperty.ReadResource);

		if (res2entry == null) {
			loadIndex();
		}
		return res2entry.keySet();
	}

	public void remove(URI entryURI) {
		if (systemEntries.contains(entryURI)) {
			throw new DisallowedException("Cannot remove system entry with URI: " + entryURI);
		}

		synchronized (this.entry.repository) {
			EntryImpl removeEntry = (EntryImpl) getByEntryURI(entryURI);
			checkAccess(removeEntry, AccessProperty.Administer);

			try {
				Iterator<URI> it = removeEntry.getReferringListsInSameContext().iterator();
				while (it.hasNext()) {
					URI uri = it.next();
					Entry listItem = getByResourceURI(uri).iterator().next();
					((ListImpl) listItem.getResource()).removeChild(entryURI, false);
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				throw new org.entrystore.repository.RepositoryException("An error occured when removing the entry from one or more lists", e);
			}

			RepositoryConnection rc = null;
			try {
				rc = entry.repository.getConnection();
				rc.begin();
				removeFromIndex(removeEntry, rc);
				removeEntry.remove(rc);
				this.entry.updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
				rc.commit();
				cache.remove(removeEntry);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(removeEntry, RepositoryEvent.EntryDeleted));
			} catch (Exception e) {
				try {
					rc.rollback();
				} catch (RepositoryException e1) {
					log.error(e1.getMessage());
					throw new org.entrystore.repository.RepositoryException("Error when rolling back transaction", e);
				}
				log.error(e.getMessage(), e);
				throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
			} finally {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
	}

	public void remove(RepositoryConnection rc) throws Exception {
		synchronized (this.entry.repository) {
			if (res2entry == null) {
				loadIndex();
			}
			for (URI entryURI : getEntries()) {			
				EntryImpl removeEntry = (EntryImpl) getByEntryURI(entryURI);

				removeFromIndex(removeEntry, rc);
				rc.clear(removeEntry.getSesameEntryURI());
				if (!systemEntries.contains(removeEntry.getEntryURI())) {
					removeEntry.remove(rc);
				}
			}
			rc.clear(this.resourceURI);
		}
	}
	
	/**
	 * @see org.entrystore.Context#hasDefaultQuota()
	 */
	public boolean hasDefaultQuota() {
		RepositoryConnection rc = null;
		try {
			rc = entry.repository.getConnection();
			return !rc.hasStatement(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI);
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		} finally {
			if (rc != null) {
				try {
					rc.close();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
		return true;
	}

	/**
	 * @see org.entrystore.Context#getQuota()
	 */
	public long getQuota() {
		if (this.quota == Quota.VALUE_UNCACHED) {
			long queriedQuota = entry.getRepositoryManager().getDefaultQuota();
			synchronized (this.entry.repository) {
				RepositoryConnection rc = null;
				try {
					rc = entry.repository.getConnection();
					List<Statement> quotaStmnt = rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI).asList();
					for (Statement statement : quotaStmnt) {
						if (statement.getObject() instanceof Literal) {
							queriedQuota = ((Literal) statement).longValue();
							break;
						}
					}
				} catch (RepositoryException re) {
					log.error(re.getMessage(), re);
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
			this.quota = queriedQuota;
		}

		return this.quota;
	}

	/**
	 * @see org.entrystore.Context#setQuota(long)
	 */
	public void setQuota(long quotaInBytes) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		URI authUserURI = pm.getAuthenticatedUserURI();
		// FIXME we do the admin check AND the admin group check because we are
		// not sure whether admin actually is in the admin group...
		if (!pm.getAdminUser().getURI().equals(authUserURI) && !pm.getAdminGroup().isMember(pm.getUser(authUserURI))) {
			log.info("Access denied, only administrators can set the allowed quota");
			throw new AuthorizationException(pm.getUser(authUserURI), entry, AccessProperty.Administer);
		}
		
		synchronized (this.entry.repository) {
			RepositoryConnection rc = null;
			try {
				rc = entry.repository.getConnection();
				rc.setAutoCommit(false);
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI), this.resourceURI);
				rc.add(this.resourceURI, RepositoryProperties.Quota, rc.getValueFactory().createLiteral(quotaInBytes), this.resourceURI);
				rc.commit();
				this.quota = quotaInBytes;
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
				try {
					rc.rollback();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
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
	
	/**
	 * @see org.entrystore.Context#removeQuota()
	 */
	public void removeQuota() {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		URI authUserURI = pm.getAuthenticatedUserURI();
		// FIXME we do the admin check AND the admin group check because we are
		// not sure whether admin actually is in the admin group...
		if (!pm.getAdminUser().getURI().equals(authUserURI) && !pm.getAdminGroup().isMember(pm.getUser(authUserURI))) {
			log.info("Access denied, only administrators can set the allowed quota");
			throw new AuthorizationException(pm.getUser(authUserURI), entry, AccessProperty.Administer);
		}
		
		synchronized (entry.repository) {
			RepositoryConnection rc = null;
			try {
				rc = entry.repository.getConnection();
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI), this.resourceURI);
				this.quota = Quota.VALUE_UNCACHED;
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
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
	
	/**
	 * @see org.entrystore.Context#getQuotaFillLevel()
	 */
	public long getQuotaFillLevel() {
		long queriedQuotaFillLevel = Quota.VALUE_UNKNOWN;
		if (this.quotaFillLevel == Quota.VALUE_UNCACHED) {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = null;
				try {
					rc = entry.repository.getConnection();
					List<Statement> quotaStmnt = rc.getStatements(this.resourceURI, RepositoryProperties.QuotaFillLevel, null, false, this.resourceURI).asList();
					for (Statement statement : quotaStmnt) {
						if (statement.getObject() instanceof Literal) {
							queriedQuotaFillLevel = ((Literal) statement.getObject()).longValue();
							break;
						}
					}
				} catch (RepositoryException re) {
					log.error(re.getMessage(), re);
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
			if (queriedQuotaFillLevel == Quota.VALUE_UNKNOWN) {
				synchronized (quotaMutex) {
					setQuotaFillLevel(recalculateQuotaFillLevel());
				}
			}
		}
		return queriedQuotaFillLevel;
	}

	/**
	 * Helper method which calculates the current quota fill level. Used inside
	 * getQuotaFillLevel, should not be called unsynchronized.
	 */
	private long recalculateQuotaFillLevel() {
		long fillLevel = 0;
		Date before = new Date();
		Set<URI> entries = getEntries();
		for (URI uri : entries) {
			Entry e = getByEntryURI(uri);
			if (EntryType.Local.equals(e.getEntryType())) {
				if (e.getResource() instanceof Data) {
					File f = ((Data) e.getResource()).getDataFile();
					if (f != null) {
						fillLevel += f.length();
					}
				}
			}
		}
		log.info("Calculation of quota fill level took " + (new Date().getTime() - before.getTime()) + " ms");
		return fillLevel;
	}
	
	/**
	 * @see org.entrystore.Context#increaseQuotaFillLevel(long)
	 */
	public void increaseQuotaFillLevel(long bytes) throws QuotaException {
		long quota = getQuota();
		synchronized (quotaMutex) {
			long newFillLevel = getQuotaFillLevel() + bytes;
			if (quota > -1 && newFillLevel > quota) {
				throw new QuotaException(QuotaException.QUOTA_EXCEEDED);
			} else {
				setQuotaFillLevel(newFillLevel);
			}
		}
	}
	
	/**
	 * @see org.entrystore.Context#decreaseQuotaFillLevel(long)
	 */
	public void decreaseQuotaFillLevel(long bytes) {
		synchronized (quotaMutex) {
			setQuotaFillLevel(getQuotaFillLevel() - bytes);
		}
	}

	/**
	 * FIXME ENTRYSTORE-418
	 *
	 * This method should only be called by increaseQuotaFillLevel() and
	 * decreaseQuotaFillLevel().
	 * 
	 * @param bytes
	 */
	private void setQuotaFillLevel(long bytes) {
		synchronized (this.entry.repository) {
			RepositoryConnection rc = null;
			try {
				rc = entry.repository.getConnection();
				rc.setAutoCommit(false);
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.QuotaFillLevel, null, false, this.resourceURI), this.resourceURI);
				rc.add(this.resourceURI, RepositoryProperties.QuotaFillLevel, rc.getValueFactory().createLiteral(bytes), this.resourceURI);
				rc.commit();
				this.quotaFillLevel = bytes;
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
				try {
					rc.rollback();
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
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

	public void initializeSystemEntries() {
	}

	protected void addSystemEntryToSystemEntries(URI uri) {
		systemEntries.add(uri);
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
			log.error(e.getMessage());
		}
	}

}