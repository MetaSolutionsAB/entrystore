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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.List;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.QuotaException;
import org.entrystore.Resource;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.security.DisallowedException;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;


public class ListImpl extends RDFResource implements List {

	Vector<URI> children;

	Log log = LogFactory.getLog(ListImpl.class);
	
	public ListImpl(EntryImpl entry, String uri) {
		super(entry, uri);
	}
	
	public ListImpl(EntryImpl entry, org.openrdf.model.URI uri) {
		super(entry, uri);
	}

	public synchronized Graph getGraph() {
		RepositoryConnection rc = null;
		Graph result = null;
		try {
			rc = entry.repository.getConnection();
			RepositoryResult<Statement> statements = rc.getStatements(null, null, null, false, this.resourceURI);
			result = new LinkedHashModel(statements.asList());
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		} finally {
			try {
				if (rc != null) {
					rc.close();
				}
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return result;
	}

	public synchronized void setGraph(Graph graph) {
		if (graph == null) {
			throw new IllegalArgumentException("Graph must not be null");
		}
		children = loadChildren(graph);
		saveChildren();
	}

	private Vector loadChildren(Graph graph) {
		if (graph == null) {
			throw new IllegalArgumentException("Graph must not be null");
		}

		Vector<URI> result = new Vector<URI>();
		for (Statement statement : graph) {
			org.openrdf.model.URI predicate = statement.getPredicate();
			if (!predicate.toString().startsWith(RDF.NAMESPACE.toString() + "_")) {
				continue;
			}

			try {
				String value = predicate.toString().substring(RDF.NAMESPACE.length());
				int index = Integer.parseInt(value.substring(value.lastIndexOf("_")+1));
				if (index > result.size()) {
					result.setSize(index);
				}
				result.set(index - 1, URI.create(statement.getObject().stringValue()));
			} catch (IndexOutOfBoundsException iobe) {
				log.error(iobe.getMessage());
			} catch (NumberFormatException nfe) {
				log.error(nfe.getMessage() + "; affected statement: " + statement);
			}
		}
		result.trimToSize();
		return result;
	}

	private synchronized void loadChildren() {
		if (children == null) {
			children = loadChildren(getGraph());
		}
	}

	private synchronized void saveChildren() {
		if (children == null) {
			return;
		}
		try {
			RepositoryConnection rc = entry.repository.getConnection();
			try {
				rc.setAutoCommit(false);
				saveChildren(rc);
				rc.commit();
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
			} catch (Exception e) {
				rc.rollback();
				log.error(e.getMessage());
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}
	}

	private void saveChildren(RepositoryConnection rc) throws RepositoryException {
		ValueFactory vf = entry.repository.getValueFactory();
		children.trimToSize();
		rc.clear(this.resourceURI);
		if (children.size() > 0) {
			rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
			for (int i = 0; i < children.size(); i++) {
				org.openrdf.model.URI li = vf.createURI(RDF.NAMESPACE+"_" + Integer.toString(i + 1));
				org.openrdf.model.URI child = vf.createURI(children.get(i).toString());
				rc.add(this.resourceURI, li, child, this.resourceURI);
			}
			entry.registerEntryModified(rc, rc.getValueFactory());
		}
	}
	
	public void addChild(URI child) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		addChild(child, true, true);
	}

	public void addChild(URI nEntry, boolean singleParentForListsRequirement, boolean orderedSetRequirement) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		boolean isOwnerOfContext = false;

		if (pm != null) {
			try {
				pm.checkAuthenticatedUserAuthorized(this.entry.getContext().getEntry(), AccessProperty.WriteResource);
				isOwnerOfContext = true;
			} catch(AuthorizationException ae) {
			}
		}
		
		EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(nEntry);
		if (singleParentForListsRequirement 
				&& childEntry.getGraphType() == GraphType.List
				&& childEntry.getReferringListsInSameContext().size() > 0) {
			throw new org.entrystore.repository.RepositoryException("The entry "+nEntry+" cannot be added since it is a list which already have another parent, try moving it instead");
		}
		if (children == null) {
			loadChildren();
		}
		if (orderedSetRequirement) {
			if (children.contains(nEntry)) {
				throw new org.entrystore.repository.RepositoryException("The entry "+nEntry+" is already a child in this list.");
			}
		}
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.setAutoCommit(false);

					if (isOwnerOfContext) {
						childEntry.setOriginalListSynchronized(null, rc, vf);
					}
					if (children.size() == 0) {
						rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
					}
					
					org.openrdf.model.URI li = vf.createURI(RDF.NAMESPACE+"_"+Integer.toString(children.size()+1));
					org.openrdf.model.URI childURI = vf.createURI(nEntry.toString());
					rc.add(this.resourceURI, li, childURI, this.resourceURI);
					childEntry.addReferringList(this, rc); //TODO deprecate addReferringList.
					children.add(nEntry);
					entry.registerEntryModified(rc, vf);
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					((EntryImpl) this.entry.getContext().getByEntryURI(nEntry)).refreshFromRepository(rc);
					rc.rollback();
					log.error(e.getMessage());
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
		}	
	}
	
	public Entry moveEntryHere(URI entry, URI fromList, boolean removeFromAllLists) throws QuotaException, IOException {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		EntryImpl e = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(entry));

		if (e == null) {
			throw new org.entrystore.repository.RepositoryException("Cannot find entry: "+entry+" and it cannot be moved.");
		}

		EntryImpl fromListEntry = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(fromList));
		if (fromListEntry == null && removeFromAllLists == false) {
			throw new org.entrystore.repository.RepositoryException("Cannot find list: "+fromList+" and hence cannot move an entry from it.");
		}
		if (fromListEntry != null) {
			if (fromListEntry.getContext() != e.getContext() 
					|| fromListEntry.getGraphType() != GraphType.List
					|| !((List) fromListEntry.getResource()).getChildren().contains(entry)) {
				throw new org.entrystore.repository.RepositoryException("Entry ("+entry+") is not a child of list ("+fromList+"), hence it cannot be moved from it.");
			}
			pm.checkAuthenticatedUserAuthorized(fromListEntry, AccessProperty.WriteResource);
		} else {
			pm.checkAuthenticatedUserAuthorized(e.getContext().getEntry(), AccessProperty.WriteResource);
		}
		
		if (e.getContext() == this.getEntry().getContext()) {
			if (fromListEntry != null) {
				//Remove from given list.
				ListImpl fromListR = (ListImpl) fromListEntry.getResource();
				fromListR.removeChild(entry, false);
			} else {
				//Remove from all lists.
				Set<URI> lists = e.getReferringListsInSameContext();
				this.addChild(entry, false, true); //Disable multiple parent check for lists.
				Context c = e.getContext();
				for (Iterator<URI> iterator = lists.iterator(); iterator.hasNext();) {
					Entry refListE = c.getByEntryURI((URI) iterator.next());
					if (refListE != null) {
						((ListImpl) refListE.getResource()).removeChild(e.getEntryURI(), false); //Disable orphan check
					}
				}
			}
			this.addChild(entry, false, true); //Disable multiple parent check for lists.
			return e;
		} else {
			//TODO change this.
			int nrOfRefLists = e.getReferringListsInSameContext().size();
			GraphType bt = e.getGraphType();
			if (bt == GraphType.SystemContext || bt == GraphType.Context || bt == GraphType.User || bt == GraphType.Group) {
				throw new org.entrystore.repository.RepositoryException("Cannot move SystemContexts, Contexts, Users or Groups.");
			}
			EntryImpl newEntry = null;
			if (bt == GraphType.List) {
				try {
					newEntry = this.copyEntryHere(e);
					((List) e.getResource()).removeTree();
				} catch (org.entrystore.repository.RepositoryException re) {
					if (newEntry != null) {
						throw new org.entrystore.repository.RepositoryException("Succedded in copying folder structure (leaving it there), but failed to remove the old structure (remove manually): "+re.getMessage());
					} else {
						throw new org.entrystore.repository.RepositoryException("Failed copying the folder structure, nothing is changed: "+re.getMessage());
					}
				}
				return newEntry;
			}
			Context c = this.entry.getContext();
			switch(e.getEntryType()) {
			case Local:
				newEntry = (EntryImpl) c.createResource(null, e.getGraphType(), e.getResourceType(), getURI());
				if (e.getGraphType() == GraphType.None && e.getResourceType() == ResourceType.InformationResource) {
					// FIXME if a QuotaException is thrown here we have already lost the original entry, this should be fixed 				
					((DataImpl) newEntry.getResource()).useData(((DataImpl) e.getResource()).getDataFile());
				}
				break;
			case Link:
				newEntry = (EntryImpl) c.createLink(null, e.getResourceURI(), getURI());
				break;
			case LinkReference:
				newEntry = (EntryImpl) c.createLinkReference(null, e.getResourceURI(), e.getExternalMetadataURI(), getURI());
				break;
			case Reference:
				newEntry = (EntryImpl) c.createReference(null, e.getResourceURI(), e.getExternalMetadataURI(), getURI());
				break;
			}
			copyGraphs(e, newEntry);
			if (removeFromAllLists || (nrOfRefLists == 1 &&  e.getReferringListsInSameContext().size() == 0)) {
				e.getContext().remove(e.getEntryURI()); //Remove the old entry, it has been succesfully copied into the new list in the new context.
			} else {
				ListImpl fromListR = (ListImpl) fromListEntry.getResource();
				fromListR.removeChild(entry, false);
			}
			return newEntry;
		}
	}

	protected EntryImpl copyEntryHere(EntryImpl entryToCopy) throws QuotaException, IOException {
		return this._copyEntryHere(entryToCopy, true);
	}
	private EntryImpl _copyEntryHere(EntryImpl entryToCopy, boolean first) throws QuotaException, IOException {
		EntryImpl newEntry = null;
		try {
		GraphType bt = entryToCopy.getGraphType();
		if (bt == GraphType.User || bt == GraphType.Context || bt == GraphType.Group || bt == GraphType.SystemContext) {
			return null;
		}
		
		Context c = this.entry.getContext();
		switch(entryToCopy.getEntryType()) {
		case Local:
			newEntry = (EntryImpl) c.createResource(null, entryToCopy.getGraphType(), entryToCopy.getResourceType(), getURI());
			if (entryToCopy.getGraphType() == GraphType.None && entryToCopy.getResourceType() == ResourceType.InformationResource) {
				// FIXME if a QuotaException is thrown here we have already lost the original entry, this should be fixed 				
				((DataImpl) newEntry.getResource()).useData(((DataImpl) entryToCopy.getResource()).getDataFile());
			}
			break;
		case Link:
			newEntry = (EntryImpl) c.createLink(null, entryToCopy.getResourceURI(), getURI());
			break;
		case LinkReference:
			newEntry = (EntryImpl) c.createLinkReference(null, entryToCopy.getResourceURI(), entryToCopy.getExternalMetadataURI(), getURI());
			break;
		case Reference:
			newEntry = (EntryImpl) c.createReference(null, entryToCopy.getResourceURI(), entryToCopy.getExternalMetadataURI(), getURI());
			break;
		}
		copyGraphs(entryToCopy, newEntry);
		if (bt == GraphType.List) {
			ListImpl newList = (ListImpl) newEntry.getResource();
			List oldList = (List) entryToCopy.getResource();
			java.util.List<URI> children = oldList.getChildren();
			for (Iterator iterator = children.iterator(); iterator.hasNext();) {
				URI uri = (URI) iterator.next();
				EntryImpl childEntryToCopy = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(uri));
				newList._copyEntryHere(childEntryToCopy, false);
			}
		}
		} catch (Exception e) {
			if (first && newEntry != null && newEntry.getGraphType() == GraphType.List) {
				((List) newEntry.getResource()).removeTree();
			}
			throw new org.entrystore.repository.RepositoryException("Failed to copy entry ("+entryToCopy.getEntryURI()+"):"+e.getMessage());
		}
		
		return newEntry;
	}

	
	private void copyGraphs(EntryImpl source, EntryImpl dest) {
		Graph eGraph = source.getGraph();
		HashMap<org.openrdf.model.URI,org.openrdf.model.URI> map = new HashMap<org.openrdf.model.URI,org.openrdf.model.URI>();
		map.put(source.getSesameEntryURI(), dest.getSesameEntryURI());
		map.put(source.getSesameLocalMetadataURI(), dest.getSesameLocalMetadataURI());
		map.put(source.getSesameResourceURI(), dest.getSesameResourceURI());
		if (source.getEntryType() == EntryType.LinkReference || source.getEntryType() == EntryType.Reference) {
			map.put(source.getSesameExternalMetadataURI(), dest.getSesameExternalMetadataURI());
			map.put(source.getSesameCachedExternalMetadataURI(), dest.getSesameCachedExternalMetadataURI());
		}
		eGraph = replaceURIs(eGraph, map);
		dest.setGraph(eGraph);

		dest.getLocalMetadata().setGraph(replaceURI(source.getLocalMetadata().getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
		if (source.getCachedExternalMetadata() != null) {
			dest.getCachedExternalMetadata().setGraph(replaceURI(source.getCachedExternalMetadata().getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
		}
		Object obj = source.getResource();
		if (obj instanceof RDFResource && !(obj instanceof List)) {
			((RDFResource) dest.getResource()).setGraph(replaceURI(((RDFResource) obj).getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
		}
	}

	private Graph replaceURI(Graph graph, org.openrdf.model.URI oUri, org.openrdf.model.URI nUri) {
		ValueFactory vf = graph.getValueFactory();
		Graph nGraph = new GraphImpl();
		for (Statement statement : graph) {
			if (statement.getSubject().equals(oUri)) {
				// replace subject URI
				nGraph.add(nUri, statement.getPredicate(), statement.getObject());
			} else if (statement.getObject().equals(oUri)) {
				// replace object URI
				nGraph.add(statement.getSubject(), statement.getPredicate(), nUri);
			} else {
				// leave everything else untouched
				nGraph.add(statement);
			}
		}
		return nGraph;
	}

	private Graph replaceURIs(Graph graph, HashMap<org.openrdf.model.URI,org.openrdf.model.URI> map) {
		ValueFactory vf = graph.getValueFactory();
		Graph nGraph = new GraphImpl();
		for (Statement statement : graph) {
			org.openrdf.model.Resource subj = statement.getSubject();
			org.openrdf.model.URI pred = statement.getPredicate();
			Value obj = statement.getObject();
			if (map.containsKey(subj)) {
				subj = (org.openrdf.model.Resource) map.get(subj);
			}
			if (map.containsKey(pred)) {
				pred = map.get(pred);
			}
			if (obj instanceof org.openrdf.model.URI && map.containsKey(obj)) {
				obj = map.get(obj);
			}
			nGraph.add(subj, pred, obj);
		}
		return nGraph;
	}

	public boolean setChildren(java.util.List<URI> newChildren) {
		return setChildren(newChildren, true, true);
	}

	public boolean setChildren(java.util.List<URI> newChildren,  boolean singleParentForListsRequirement, boolean orderedSetRequirement) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		boolean isOwnerOfContext = false;

		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
			try {
				pm.checkAuthenticatedUserAuthorized(this.entry.getContext().getEntry(), AccessProperty.WriteResource);
				isOwnerOfContext = true;
			} catch(AuthorizationException ae) {
			}
		}
		
		if (children == null) {
			loadChildren();
		}
		java.util.List<URI> toRemove = new java.util.ArrayList<URI>(children);
		toRemove.removeAll(newChildren);
		java.util.List<URI> toAdd = new java.util.ArrayList<URI>(newChildren);
		toAdd.removeAll(children);
		
		for (URI uri : toAdd) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (singleParentForListsRequirement 
					&& childEntry.getGraphType() == GraphType.List
					&& !childEntry.getReferringListsInSameContext().isEmpty()) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child "+uri+" is a list which already have a parent.");
			}
			if (childEntry == null) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child "+uri+" does not exist.");
			}
		}
		
		if (orderedSetRequirement) {
			HashSet<URI> set = new HashSet<URI>(newChildren);
			if (set.size() < newChildren.size()) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since some of its children occur multiple times.");
			}
		}

		for (URI uri : toRemove) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry == null) {
				log.warn("List contains entry which does not exist: " + uri);
				continue;
			}
			if (!canRemove(true, childEntry, isOwnerOfContext)) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since you do not have the rights to remove the child "+uri+" from the list.");
			}
		}
		
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				Vector<URI> oldChildrenList = children;
				try {
					rc.setAutoCommit(false);
					children = new Vector<URI>(newChildren);
					saveChildren(rc);
					for (URI uri : toAdd) {
						EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri); 
						if (childEntry != null) {
							childEntry.addReferringList(this, rc); //TODO deprecate addReferringList.
							if (isOwnerOfContext) {
								childEntry.setOriginalListSynchronized(null, rc, entry.repository.getValueFactory());
							}
							entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						}
					}
					for (URI uri : toRemove) {
						EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
						if (childEntry != null) {
							childEntry.removeReferringList(this, rc); //TODO deprecate removeReferringList.
							if (isOwnerOfContext) {
								childEntry.setOriginalListSynchronized(null, rc, entry.repository.getValueFactory());
							}
							entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						}
					}
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					log.error(e.getMessage());
					rc.rollback();
					for (URI uri : toAdd) {
						EntryImpl childEntry = ((EntryImpl) this.entry.getContext().getByEntryURI(uri));
						childEntry.refreshFromRepository(rc);
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
					}
					for (URI uri : toRemove) {
						EntryImpl childEntry = ((EntryImpl) this.entry.getContext().getByEntryURI(uri));
						childEntry.refreshFromRepository(rc);
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
					}
					children = oldChildrenList;
					throw new org.entrystore.repository.RepositoryException("Cannot set the list since: "+e.getMessage());
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
		}
		return true;
	}

	public java.util.List<URI> getChildren() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.ReadResource);

		if (children == null) {
			loadChildren();
		}
		return Collections.unmodifiableList(children);
	}

	public void moveChildAfter(URI child, URI afterChild) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);

		if (children == null) {
			loadChildren();
		}
		synchronized (this.entry.repository) {
			children.remove(child);
			children.add(children.indexOf(afterChild)+1, child);
			saveChildren();
		}
	}

	public void moveChildBefore(URI child, URI beforeChild) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);

		if (children == null) {
			loadChildren();
		}
		synchronized (this.entry.repository) {
			children.remove(child);
			children.add(children.indexOf(beforeChild), child);
			saveChildren();
		}
	}

	public boolean removeChild(URI child) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		return removeChild(child, true);
	}
	
	protected boolean removeChild(URI child, boolean checkOrphaned) {
		
		if (children == null) {
			loadChildren();
		}
		if (!children.contains(child)) {
			return false;
		}

		synchronized (this.entry.repository) {
			boolean isOwnerOfContext = true;
			PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
			if (pm != null) {
				try {
					pm.checkAuthenticatedUserAuthorized(this.entry.getContext().getEntry(), AccessProperty.WriteResource);
				} catch(AuthorizationException ae) {
					isOwnerOfContext = false;
				}
			}
			
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(child);
			if ((childEntry != null) && canRemove(checkOrphaned, childEntry, isOwnerOfContext)) {
				children.remove(child);
				try {
					RepositoryConnection rc = entry.repository.getConnection();
					ValueFactory vf = entry.repository.getValueFactory();
					try {
						rc.setAutoCommit(false);
						if (checkOrphaned && isOwnerOfContext) {
							childEntry.setOriginalListSynchronized(null, rc, vf); //remains to do the same for list case.
						}
						saveChildren(rc);
						childEntry.removeReferringList(this, rc);
						rc.commit();
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					} catch (Exception e) {
						log.error(e.getMessage());
						rc.rollback();
						childEntry.refreshFromRepository(rc);
						return false;
					} finally {
						rc.close();
					}
				} catch (RepositoryException e) {
					log.error(e.getMessage());
				}
				return true;
			}
			return false;
		}
	}

	private boolean canRemove(boolean checkOrphaned, EntryImpl childEntry, boolean isOwnerOfContext) {
		if (childEntry == null) {
			return false;
		}
		Set<java.net.URI> refLists = childEntry.getReferringListsInSameContext();
		if ((refLists != null) && checkOrphaned && refLists.size() == 1) {
			if (isOwnerOfContext) {
				return true;
			} else {
				return childEntry.getOriginalList() == null;
			}
		}
		return true;
	}
	
	public void remove(RepositoryConnection rc) throws Exception {
		synchronized (this.entry.repository) {
			if (children == null) {
				loadChildren();
			}
			rc.clear(this.resourceURI);
			for (URI uri : children) {
				EntryImpl childEntry = ((EntryImpl) this.entry.getContext().getByEntryURI(uri));
				childEntry.removeReferringList(this, rc);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
			}
			children = null;
			entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceDeleted));
		}
	}
	
	public void removeTree() {
		Context c = this.entry.getContext();
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(c.getEntry(), AccessProperty.Administer);
		if (children == null) {
			loadChildren();
		}
		
		java.util.List<URI> tchildren = new ArrayList<URI>(children);
		setChildren(new ArrayList<URI>(), false, false);
		for (URI uri : tchildren) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry != null) {
				if (GraphType.List.equals(childEntry.getGraphType()) && EntryType.Local.equals(childEntry.getEntryType())) {
					((List) childEntry.getResource()).removeTree();
				} else if (childEntry.getReferringListsInSameContext().size() == 0) {
					c.remove(uri);
				}
			}
		}
		try {
			c.remove(this.getEntry().getEntryURI());
		} catch (DisallowedException e) { //If a system entry, e.g. like _top or _all it is not allowed to be removed.
		}
	}
	
	public void applyACLtoChildren(boolean recursive) {
		Context c = this.entry.getContext();
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(c.getEntry(), AccessProperty.Administer);
		if (children == null) {
			loadChildren();
		}
		
		for (URI uri : children) {
			Entry childEntry = entry.getContext().getByEntryURI(uri);
			if (childEntry != null) {
				for (AccessProperty ap : AccessProperty.values()) {
					childEntry.setAllowedPrincipalsFor(ap, entry.getAllowedPrincipalsFor(ap));
				}
				if (GraphType.List.equals(childEntry.getGraphType()) && EntryType.Local.equals(childEntry.getEntryType())) {
					Resource childResource = childEntry.getResource();
					if (childResource instanceof List) {
						((List) childEntry.getResource()).applyACLtoChildren(recursive);
					} else {
						log.warn("Entry has builtin type List but its resource is not instance of List, please check: " + uri);
					}
				}
			}
		}
	}

}