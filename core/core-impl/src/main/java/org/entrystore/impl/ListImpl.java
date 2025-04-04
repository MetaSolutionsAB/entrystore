/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

public class ListImpl extends RDFResource implements List {

	private static final Logger log = LoggerFactory.getLogger(ListImpl.class);

	private Vector<URI> children;

	public ListImpl(EntryImpl entry, String uri) {
		super(entry, uri);
	}

	public ListImpl(EntryImpl entry, IRI uri) {
		super(entry, uri);
	}

	public synchronized Model getGraph() {
		RepositoryConnection rc = null;
		Model result = null;
		try {
			rc = entry.repository.getConnection();
			RepositoryResult<Statement> statements = rc.getStatements(null, null, null, false, this.resourceURI);
			result = new LinkedHashModel(Iterations.asList(statements));
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

	public synchronized void setGraph(Model graph) {
		if (graph == null) {
			throw new IllegalArgumentException("Graph must not be null");
		}
		children = loadChildren(graph);
		saveChildren();
	}

	private Vector<URI> loadChildren(Model graph) {
		if (graph == null) {
			throw new IllegalArgumentException("Graph must not be null");
		}

		Vector<URI> result = new Vector<>();
		for (Statement statement : graph) {
			IRI predicate = statement.getPredicate();
			if (!predicate.toString().startsWith(RDF.NAMESPACE + "_")) {
				continue;
			}

			try {
				String value = predicate.toString().substring(RDF.NAMESPACE.length());
				int index = Integer.parseInt(value.substring(value.lastIndexOf("_") + 1));
				if (index > result.size()) {
					result.setSize(index);
				}
				result.set(index - 1, URI.create(statement.getObject().stringValue()));
			} catch (IndexOutOfBoundsException iobe) {
				log.error(iobe.getMessage());
			} catch (NumberFormatException nfe) {
				log.error("{}; affected statement: {}", nfe.getMessage(), statement);
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
				rc.begin();
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
		if (!children.isEmpty()) {
			rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
			for (int i = 0; i < children.size(); i++) {
				IRI li = vf.createIRI(RDF.NAMESPACE + "_" + (i + 1));
				IRI child = vf.createIRI(children.get(i).toString());
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
			} catch (AuthorizationException ae) {
			}
		}

		EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(nEntry);
		if (singleParentForListsRequirement
			&& childEntry.getGraphType() == GraphType.List
			&& !childEntry.getReferringListsInSameContext().isEmpty()) {
			throw new org.entrystore.repository.RepositoryException("The entry " + nEntry + " cannot be added since it is a list which already have another parent, try moving it instead");
		}
		if (children == null) {
			loadChildren();
		}
		if (orderedSetRequirement) {
			if (children.contains(nEntry)) {
				throw new org.entrystore.repository.RepositoryException("The entry " + nEntry + " is already a child in this list.");
			}
		}
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.begin();

					if (isOwnerOfContext) {
						childEntry.setOriginalListSynchronized(null, rc, vf);
					}
					if (children.isEmpty()) {
						rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
					}

					IRI li = vf.createIRI(RDF.NAMESPACE + "_" + (children.size() + 1));
					IRI childURI = vf.createIRI(nEntry.toString());
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

	public Entry moveEntryHere(URI entry, URI fromList, boolean removeFromAllLists) throws QuotaException {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		pm.checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		EntryImpl e = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(entry));

		if (e == null) {
			throw new org.entrystore.repository.RepositoryException("Cannot find entry: " + entry + " and it cannot be moved.");
		}

		EntryImpl fromListEntry = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(fromList));
		if (fromListEntry == null && !removeFromAllLists) {
			throw new org.entrystore.repository.RepositoryException("Cannot find list: " + fromList + " and hence cannot move an entry from it.");
		}
		if (fromListEntry != null) {
			if (fromListEntry.getContext() != e.getContext()
				|| fromListEntry.getGraphType() != GraphType.List
				|| !((List) fromListEntry.getResource()).getChildren().contains(entry)) {
				throw new org.entrystore.repository.RepositoryException("Entry (" + entry + ") is not a child of list (" + fromList + "), hence it cannot be moved from it.");
			}
			pm.checkAuthenticatedUserAuthorized(fromListEntry, AccessProperty.WriteResource);
		} else {
			pm.checkAuthenticatedUserAuthorized(e.getContext().getEntry(), AccessProperty.WriteResource);
		}

		if (e.getContext() == this.getEntry().getContext()) {
			if (fromListEntry != null) {
				//Remove from a given list.
				ListImpl fromListR = (ListImpl) fromListEntry.getResource();
				fromListR.removeChild(entry, false);
			} else {
				//Remove from all lists.
				Set<URI> lists = e.getReferringListsInSameContext();
				this.addChild(entry, false, true); //Disable multiple parent check for lists.
				Context c = e.getContext();
				for (URI list : lists) {
					Entry refListE = c.getByEntryURI(list);
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
						throw new org.entrystore.repository.RepositoryException("Succeeded in copying folder structure (leaving it there), but failed to remove the old structure (remove manually): " + re.getMessage());
					} else {
						throw new org.entrystore.repository.RepositoryException("Failed copying the folder structure, nothing is changed: " + re.getMessage());
					}
				}
				return newEntry;
			}
			Context c = this.entry.getContext();
			switch (e.getEntryType()) {
				case Local:
					newEntry = (EntryImpl) c.createResource(null, e.getGraphType(), e.getResourceType(), getURI());
					if (e.getGraphType() == GraphType.None && e.getResourceType() == ResourceType.InformationResource) {
						// FIXME if a QuotaException is thrown here we have already lost the original entry, this should be fixed
						try {
							((DataImpl) newEntry.getResource()).useData(((DataImpl) e.getResource()).getDataFile());
						} catch (IOException ex) {
							log.error(ex.getMessage(), ex);
						}
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
			if (removeFromAllLists || (nrOfRefLists == 1 && e.getReferringListsInSameContext().isEmpty())) {
				e.getContext().remove(e.getEntryURI()); //Remove the old entry, it has been successfully copied into the new list in the new context.
			} else {
				ListImpl fromListR = (ListImpl) fromListEntry.getResource();
				fromListR.removeChild(entry, false);
			}
			return newEntry;
		}
	}

	protected EntryImpl copyEntryHere(EntryImpl entryToCopy) throws QuotaException {
		return this._copyEntryHere(entryToCopy, true);
	}

	private EntryImpl _copyEntryHere(EntryImpl entryToCopy, boolean first) throws QuotaException {
		EntryImpl newEntry = null;
		try {
			GraphType bt = entryToCopy.getGraphType();
			if (bt == GraphType.User || bt == GraphType.Context || bt == GraphType.Group || bt == GraphType.SystemContext) {
				return null;
			}

			Context c = this.entry.getContext();
			switch (entryToCopy.getEntryType()) {
				case Local:
					newEntry = (EntryImpl) c.createResource(null, entryToCopy.getGraphType(), entryToCopy.getResourceType(), getURI());
					if (entryToCopy.getGraphType() == GraphType.None && entryToCopy.getResourceType() == ResourceType.InformationResource) {
						// FIXME if a QuotaException is thrown here we have already lost the original entry, this should be fixed
						try {
							((DataImpl) newEntry.getResource()).useData(((DataImpl) entryToCopy.getResource()).getDataFile());
						} catch (IOException ex) {
							log.error(ex.getMessage(), ex);
						}
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
				for (URI uri : children) {
					EntryImpl childEntryToCopy = ((EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(uri));
					newList._copyEntryHere(childEntryToCopy, false);
				}
			}
		} catch (Exception e) {
			if (first && newEntry != null && newEntry.getGraphType() == GraphType.List) {
				((List) newEntry.getResource()).removeTree();
			}
			throw new org.entrystore.repository.RepositoryException("Failed to copy entry (" + entryToCopy.getEntryURI() + "):" + e.getMessage());
		}

		return newEntry;
	}


	private void copyGraphs(EntryImpl source, EntryImpl dest) {
		Model eGraph = source.getGraph();
		HashMap<IRI, IRI> map = new HashMap<IRI, IRI>();
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

	private Model replaceURI(Model graph, IRI oUri, IRI nUri) {
		Model nGraph = new LinkedHashModel();
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

	private Model replaceURIs(Model graph, HashMap<IRI, IRI> map) {
		Model nGraph = new LinkedHashModel();
		for (Statement statement : graph) {
			org.eclipse.rdf4j.model.Resource subj = statement.getSubject();
			IRI pred = statement.getPredicate();
			Value obj = statement.getObject();
			if (map.containsKey(subj)) {
				subj = (org.eclipse.rdf4j.model.Resource) map.get(subj);
			}
			if (map.containsKey(pred)) {
				pred = map.get(pred);
			}
			if (obj instanceof IRI && map.containsKey(obj)) {
				obj = map.get(obj);
			}
			nGraph.add(subj, pred, obj);
		}
		return nGraph;
	}

	public boolean setChildren(java.util.List<URI> newChildren) {
		return setChildren(newChildren, true, true);
	}

	public boolean setChildren(java.util.List<URI> newChildren, boolean singleParentForListsRequirement, boolean orderedSetRequirement) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		boolean isOwnerOfContext = false;

		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
			try {
				pm.checkAuthenticatedUserAuthorized(this.entry.getContext().getEntry(), AccessProperty.WriteResource);
				isOwnerOfContext = true;
			} catch (AuthorizationException ignored) {
			}
		}

		if (children == null) {
			loadChildren();
		}
		java.util.List<URI> toRemove = new java.util.ArrayList<>(children);
		toRemove.removeAll(newChildren);
		java.util.List<URI> toAdd = new java.util.ArrayList<>(newChildren);
		toAdd.removeAll(children);

		for (URI uri : toAdd) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (singleParentForListsRequirement
				&& childEntry.getGraphType() == GraphType.List
				&& !childEntry.getReferringListsInSameContext().isEmpty()) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child " + uri + " is a list which already have a parent.");
			}
			if (childEntry == null) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child " + uri + " does not exist.");
			}
		}

		if (orderedSetRequirement) {
			HashSet<URI> set = new HashSet<>(newChildren);
			if (set.size() < newChildren.size()) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since some of its children occur multiple times.");
			}
		}

		for (URI uri : toRemove) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry == null) {
				log.warn("List contains entry which does not exist: {}", uri);
				continue;
			}
			if (!canRemove(true, childEntry, isOwnerOfContext)) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since you do not have the rights to remove the child " + uri + " from the list.");
			}
		}

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = entry.repository.getConnection();
				Vector<URI> oldChildrenList = children;
				try {
					rc.begin();
					children = new Vector<>(newChildren);
					java.util.List<EntryImpl> updatedChildEntries = new ArrayList<>();
					saveChildren(rc);
					for (URI uri : toAdd) {
						EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
						if (childEntry != null) {
							childEntry.addReferringList(this, rc); //TODO deprecate addReferringList.
							if (isOwnerOfContext) {
								childEntry.setOriginalListSynchronized(null, rc, entry.repository.getValueFactory());
							}
							updatedChildEntries.add(childEntry);
						}
					}
					for (URI uri : toRemove) {
						EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
						if (childEntry != null) {
							childEntry.removeReferringList(this, rc); //TODO deprecate removeReferringList.
							if (isOwnerOfContext) {
								childEntry.setOriginalListSynchronized(null, rc, entry.repository.getValueFactory());
							}
							updatedChildEntries.add(childEntry);
						}
					}
					rc.commit();

					for (EntryImpl updatedChildEntry : updatedChildEntries) {
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(updatedChildEntry, RepositoryEvent.RelationsUpdated));
					}
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
					throw new org.entrystore.repository.RepositoryException("Cannot set the list since: " + e.getMessage());
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
			children.add(children.indexOf(afterChild) + 1, child);
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
				} catch (AuthorizationException ae) {
					isOwnerOfContext = false;
				}
			}

			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(child);
			if (canRemove(checkOrphaned, childEntry, isOwnerOfContext)) {
				children.remove(child);
				try {
					RepositoryConnection rc = entry.repository.getConnection();
					ValueFactory vf = entry.repository.getValueFactory();
					try {
						rc.begin();
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
		Set<URI> refLists = childEntry.getReferringListsInSameContext();
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

		java.util.List<URI> tchildren = new ArrayList<>(children);
		setChildren(new ArrayList<>(), false, false);
		for (URI uri : tchildren) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry != null) {
				if (GraphType.List.equals(childEntry.getGraphType()) && EntryType.Local.equals(childEntry.getEntryType())) {
					((List) childEntry.getResource()).removeTree();
				} else if (childEntry.getReferringListsInSameContext().isEmpty()) {
					c.remove(uri);
				}
			}
		}
		try {
			c.remove(this.getEntry().getEntryURI());
		} catch (DisallowedException e) { // If a system entry, e.g., like _top or _all it is not allowed to be removed.
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
						log.warn("Entry has builtin type List but its resource is not instance of List, please check: {}", uri);
					}
				}
			}
		}
	}

}
