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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Context;
import se.kmr.scam.repository.DisallowedException;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.List;
import se.kmr.scam.repository.LocationType;
import se.kmr.scam.repository.QuotaException;
import se.kmr.scam.repository.RepositoryEvent;
import se.kmr.scam.repository.RepositoryEventObject;
import se.kmr.scam.repository.RepresentationType;
import se.kmr.scam.repository.Resource;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;

public class ListImpl extends RDFResource implements List {
	Vector<URI> children;
	Log log = LogFactory.getLog(ListImpl.class);
	
	public ListImpl(EntryImpl entry, String uri) {
		super(entry, uri);
	}
	
	public ListImpl(EntryImpl entry, org.openrdf.model.URI uri) {
		super(entry, uri);
	}

	public void loadChildren() {
		try {
			synchronized (this.entry.repository) {
				if (children != null) {
					return;
				}
				children = new Vector<URI>();
				
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					RepositoryResult<Statement> statements = rc.getStatements(null, null, null, false, this.resourceURI);
					while (statements.hasNext()) {
						Statement statement = statements.next();
						org.openrdf.model.URI predicate = statement.getPredicate();
						if (!predicate.toString().startsWith(RDF.NAMESPACE.toString() + "_")) {
							continue;
						}
						
						try {
//							children.add(URI.create(statement.getObject().stringValue())); 
							String value = predicate.toString().substring(RDF.NAMESPACE.length());
							int index = Integer.parseInt(value.substring(value.lastIndexOf("_")+1));
//							children.ensureCapacity(index);
							
							if (index > children.size()) {
								children.setSize(index); 
							}
							
							children.set(index-1, URI.create(statement.getObject().stringValue()));
						} catch (IndexOutOfBoundsException iobe) {
							log.error("loadChildren() " + iobe.getClass().getSimpleName() + ": " + iobe.getMessage());
						} catch (NumberFormatException nfe) {
							log.error("loadChildren() " + nfe.getClass().getSimpleName() + ": " + nfe.getMessage());
							log.error("Causing statement: " + statement);
						}
					}
					children.trimToSize();
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
		}		
	}

	private void saveChildren() {
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
				e.printStackTrace();
			} finally {
				rc.close();
			}
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}

	private void saveChildren(RepositoryConnection rc) throws RepositoryException {
		ValueFactory vf = entry.repository.getValueFactory();
		children.trimToSize();
		rc.clear(this.resourceURI);
		if (children.size() > 0) {
			rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
			for (int i = 0; i < children.size();i++) {						
				org.openrdf.model.URI li = vf.createURI(RDF.NAMESPACE+"_"+Integer.toString(i+1));
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
		EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(nEntry);
		if (singleParentForListsRequirement 
				&& childEntry.getBuiltinType() == BuiltinType.List
				&& childEntry.getReferringListsInSameContext().size() > 0) {
			throw new se.kmr.scam.repository.RepositoryException("The entry "+nEntry+" cannot be added since it is a list which already have another parent, try moving it instead");			
		}
		if (children == null) {
			loadChildren();
		}
		if (orderedSetRequirement) {
			if (children.contains(nEntry)) {
				throw new se.kmr.scam.repository.RepositoryException("The entry "+nEntry+" is already a child in this list.");
			}
		}
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.setAutoCommit(false);

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
					e.printStackTrace();
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
		}	
	}
	
	public Entry moveEntryHere(URI entry, URI fromList) throws QuotaException, IOException {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		EntryImpl fromListEntry = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(fromList));
		EntryImpl e = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(entry));
		
		if (fromListEntry == null) {
			throw new se.kmr.scam.repository.RepositoryException("Cannot find list: "+fromList+" and hence cannot move an entry from it.");
		}
		if (e == null) {
			throw new se.kmr.scam.repository.RepositoryException("Cannot find entry: "+entry+" and it cannot be moved.");			
		}
		if (fromListEntry.getContext() != e.getContext() 
				|| fromListEntry.getBuiltinType() != BuiltinType.List
				|| !((List) fromListEntry.getResource()).getChildren().contains(entry)) {
			throw new se.kmr.scam.repository.RepositoryException("Entry ("+entry+") is not a child of list ("+fromList+"), hence it cannot be moved from it.");
		}

		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(fromListEntry, AccessProperty.WriteResource);
		
		ListImpl fromListR = (ListImpl) fromListEntry.getResource();
		if (fromListEntry.getContext() == this.getEntry().getContext()) {
			fromListR.removeChild(entry, false); //Disable orphan check
			this.addChild(entry, false, true); //Disable multiple parent check for lists.
			return e;
		} else {
			if (e.getReferringListsInSameContext().size() != 1) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot move entry: "+entry+" to another context since it appears in several lists.");				
			}
			if (e.getBuiltinType() != BuiltinType.None) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot move entry: "+entry+" to another context since the builtin type is not none.");
			}
			fromListR.removeChild(entry, false);
			Context c = this.entry.getContext();
			EntryImpl newEntry = null;
			switch(e.getLocationType()) {
			case Local:
				newEntry = (EntryImpl) c.createResource(e.getBuiltinType(), e.getRepresentationType(), getURI());
				if (e.getBuiltinType() == BuiltinType.None && e.getRepresentationType() == RepresentationType.InformationResource) {
					// FIXME if a QuotaException is thrown here we have already lost the original entry, this should be fixed 				
					((DataImpl) newEntry.getResource()).useData(((DataImpl) e.getResource()).getDataFile());
				}
				break;
			case Link:
				newEntry = (EntryImpl) c.createLink(e.getResourceURI(), getURI());
				break;
			case LinkReference:
				newEntry = (EntryImpl) c.createLinkReference(e.getResourceURI(), e.getExternalMetadataURI(), getURI());
				break;
			case Reference:
				newEntry = (EntryImpl) c.createReference(e.getResourceURI(), e.getExternalMetadataURI(), getURI());
				break;
			}
			copyGraphs(e, newEntry);
			e.getContext().remove(e.getEntryURI()); //Remove the old entry, it has been succesfully copied into the new list in the new context.
			return newEntry;
		}
	}
	
	private void copyGraphs(EntryImpl source, EntryImpl dest) {
		Graph eGraph = source.getGraph();
		eGraph = replaceURI(eGraph, source.getSesameEntryURI(), dest.getSesameEntryURI());
		eGraph = replaceURI(eGraph, source.getSesameLocalMetadataURI(), dest.getSesameLocalMetadataURI());
		eGraph = replaceURI(eGraph, source.getSesameResourceURI(), dest.getSesameResourceURI());
		if (source.getLocationType() == LocationType.LinkReference || source.getLocationType() == LocationType.Reference) {
			eGraph = replaceURI(eGraph, source.getSesameExternalMetadataURI(), dest.getSesameExternalMetadataURI());			
			eGraph = replaceURI(eGraph, source.getSesameCachedExternalMetadataURI(), dest.getSesameCachedExternalMetadataURI());			
		}
		dest.setGraph(eGraph);

		dest.getLocalMetadata().setGraph(replaceURI(source.getLocalMetadata().getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
		if (source.getCachedExternalMetadata() != null) {
			dest.getCachedExternalMetadata().setGraph(replaceURI(source.getCachedExternalMetadata().getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
		}
		Object obj = source.getResource();
		if (obj instanceof RDFResource) {
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

	public boolean setChildren(java.util.List<URI> newChildren) {
		return setChildren(newChildren, true, true);
	}

	public boolean setChildren(java.util.List<URI> newChildren,  boolean singleParentForListsRequirement, boolean orderedSetRequirement) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		
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
					&& childEntry.getBuiltinType() == BuiltinType.List 
					&& !childEntry.getReferringListsInSameContext().isEmpty()) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot set the list since the child "+uri+" is a list which already have a parent.");
			}
			if (childEntry == null) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot set the list since the child "+uri+" does not exist.");
			}
		}
		
		if (orderedSetRequirement) {
			HashSet<URI> set = new HashSet<URI>(newChildren);
			if (set.size() < newChildren.size()) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot set the list since some of its children occur multiple times.");				
			}
		}

		for (URI uri : toRemove) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry == null) {
				log.warn("List contains entry which does not exist: " + uri);
				continue;
			}
			if (!canRemove(true, childEntry)) {
				throw new se.kmr.scam.repository.RepositoryException("Cannot set the list since you do not have the rights to remove the child "+uri+" from the list.");
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
							entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						}
					}
					for (URI uri : toRemove) {
						EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
						if (childEntry != null) {
							childEntry.removeReferringList(this, rc); //TODO deprecate removeReferringList.
							entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						}
					}
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					e.printStackTrace();
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
					throw new se.kmr.scam.repository.RepositoryException("Cannot set the list since: "+e.getMessage());
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
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(child);
			if ((childEntry != null) && canRemove(checkOrphaned, childEntry)) {
				children.remove(child);
				try {
					RepositoryConnection rc = entry.repository.getConnection();
					ValueFactory vf = entry.repository.getValueFactory();
					try {
						rc.setAutoCommit(false);
						saveChildren(rc);
						childEntry.removeReferringList(this, rc);
						rc.commit();
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
					} catch (Exception e) {
						e.printStackTrace();
						rc.rollback();
						childEntry.refreshFromRepository(rc);
						return false;
					} finally {
						rc.close();
					}
				} catch (RepositoryException e) {
					e.printStackTrace();
				}
				return true;
			}
			return false;
		}
	}

	private boolean canRemove(boolean checkOrphaned, EntryImpl childEntry) {
		if (childEntry == null) {
			return false;
		}
		Set<java.net.URI> refLists = childEntry.getReferringListsInSameContext();
		if ((refLists != null) && checkOrphaned && refLists.size() == 1) {
			try {
				EntryImpl contextEntry = ((ContextImpl) this.entry.getContext()).entry;
				this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(contextEntry, AccessProperty.WriteResource);
			} catch (AuthorizationException ae) {
				// Not allowed to remove since user has access only to this list and the only 
				// use of the child is in this list. I.e. the user are not allowed to orphan 
				// resources unless he or she has write access to the context.
				return false; 
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
			entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
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
				if (BuiltinType.List.equals(childEntry.getBuiltinType()) && LocationType.Local.equals(childEntry.getLocationType())) {
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
				if (BuiltinType.List.equals(childEntry.getBuiltinType()) && LocationType.Local.equals(childEntry.getLocationType())) {
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