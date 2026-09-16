/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

import lombok.extern.slf4j.Slf4j;
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

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

@Slf4j
public class ListImpl extends RDFResource implements List {

	/**
	 * Guards every read and write of {@link #children}. Per list rather than {@code entry.repository}, which is
	 * one object shared by every entry in the instance: holding that across the store read in {@link #getGraph()}
	 * blocks each cold list read for the duration of an import (45cdc777 moved the analogous lazy
	 * {@code ContextImpl.loadIndex()} off it for the same reason).
	 * <p>
	 * <b>This lock is innermost. Never acquire {@code entry.repository} while holding it.</b> The mutators take
	 * {@code entry.repository} first and reach this one through {@link #loadChildren()}, so acquiring them in the
	 * other order anywhere would deadlock every write in the instance.
	 */
	private final Object childrenLock = new Object();

	/**
	 * The members of this list, or null until they are loaded. Read and written only under {@link #childrenLock},
	 * which {@link #loadChildren()} holds across the load and {@link #invalidateChildren()} across the drop: a
	 * reader that is loading therefore publishes its copy <i>before</i> a following invalidation drops it, never
	 * after. That ordering is exactly why a caller that changed the list through its own transaction must still
	 * invalidate after the commit. Volatile so {@link #getChildren()} can serve a loaded list without the lock.
	 */
	private volatile Vector<URI> children;

	public ListImpl(EntryImpl entry, String uri) {
		super(entry, uri);
	}

	public ListImpl(EntryImpl entry, IRI uri) {
		super(entry, uri);
	}

	public Model getGraph() {
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

	public void setGraph(Model graph) {
		if (graph == null) {
			throw new IllegalArgumentException("Graph must not be null");
		}
		setChildren(loadChildren(graph));
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

	/**
	 * Returns the members, loading them from the store on first access. Callers must work with the returned
	 * vector instead of reading the field again, since {@link #invalidateChildren()} can null the field as soon
	 * as this monitor is released.
	 */
	private Vector<URI> loadChildren() {
		synchronized (childrenLock) {
			if (children == null) {
				children = loadChildren(getGraph());
			}
			return children;
		}
	}

	private void saveChildren() {
		synchronized (this.entry.repository) {
			try {
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					rc.begin();
					saveChildren(rc);
					rc.commit();
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					rc.rollback();
					throw new org.entrystore.repository.RepositoryException("Failed to save children for entry " + entry.getId(), e);
				} finally {
					rc.close();
				}
			} catch (RepositoryException e) {
				throw new org.entrystore.repository.RepositoryException("Failed to obtain repository connection for entry " + entry.getId(), e);
			}
		}
	}

	/** Package-private so a same-package test can make a member-list write fail and exercise the recovery path. */
	void saveChildren(RepositoryConnection rc) throws RepositoryException {
		saveChildren(loadChildren(), rc);
	}

	private void saveChildren(Vector<URI> childrenToSave, RepositoryConnection rc) throws RepositoryException {
		ValueFactory vf = entry.repository.getValueFactory();
		childrenToSave.trimToSize();
		rc.clear(this.resourceURI);
		if (!childrenToSave.isEmpty()) {
			rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
			for (int i = 0; i < childrenToSave.size(); i++) {
				IRI li = vf.createIRI(RDF.NAMESPACE + "_" + (i + 1));
				IRI child = vf.createIRI(childrenToSave.get(i).toString());
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
		if (orderedSetRequirement && loadChildren().contains(nEntry)) {
			throw new org.entrystore.repository.RepositoryException("The entry " + nEntry + " is already a child in this list.");
		}
		try {
			synchronized (this.entry.repository) {
				Vector<URI> currentChildren = loadChildren();
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					ValueFactory vf = entry.repository.getValueFactory();
					rc.begin();

					if (isOwnerOfContext) {
						childEntry.setOriginalListSynchronized(null, rc, vf);
					}
					if (currentChildren.isEmpty()) {
						rc.add(this.resourceURI, RDF.TYPE, RDF.SEQ, this.resourceURI);
					}

					IRI li = vf.createIRI(RDF.NAMESPACE + "_" + (currentChildren.size() + 1));
					IRI childURI = vf.createIRI(nEntry.toString());
					rc.add(this.resourceURI, li, childURI, this.resourceURI);
					childEntry.addReferringList(this, rc); //TODO deprecate addReferringList.
					entry.registerEntryModified(rc, vf);
					rc.commit();
					currentChildren.add(nEntry);
				} catch (Exception e) {
					try {
						rc.rollback();
						childEntry.refreshFromRepository(rc);
					} catch (Exception recoveryEx) {
						e.addSuppressed(recoveryEx);
					}
					throw new org.entrystore.repository.RepositoryException("Failed to add child " + nEntry + " to list " + getURI(), e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to obtain repository connection for entry " + entry.getId(), e);
		}

		// Notify listeners only after the transaction has committed and the connection is closed, and outside the
		// try/catch above, so a listener failure propagates on its own and is never misreported as a failed add.
		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
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
			newEntry = createCopyHere(e);
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

	/**
	 * Creates an entry of the same kind as the given one as a child of this list, without copying any
	 * graphs — the caller does that through {@link #copyGraphs}.
	 * <p>
	 * For a local information resource the new entry gets its own byte copy of the source's data file
	 * (DataImpl.useData copies it), charged against this list's context quota.
	 *
	 * @return the new entry, never null.
	 */
	private EntryImpl createCopyHere(EntryImpl source) {
		Context c = this.entry.getContext();
		return switch (source.getEntryType()) {
			case Local -> {
				EntryImpl newEntry = (EntryImpl) c.createResource(null, source.getGraphType(), source.getResourceType(), getURI());
				if (source.getGraphType() == GraphType.None
					&& source.getResourceType() == ResourceType.InformationResource) {
					try {
						((DataImpl) newEntry.getResource()).useData(((DataImpl) source.getResource()).getDataFile());
					} catch (IOException ex) {
						// FIXME data loss on the move path: this leaves the copy empty and _moveEntryHere
						// then removes the source. Propagate instead of logging (_copyEntryHere is safe).
						log.error(ex.getMessage(), ex);
					}
				}
				yield newEntry;
			}
			case Link -> (EntryImpl) c.createLink(null, source.getResourceURI(), getURI());
			case LinkReference -> (EntryImpl) c.createLinkReference(null, source.getResourceURI(), source.getExternalMetadataURI(), getURI());
			case Reference -> (EntryImpl) c.createReference(null, source.getResourceURI(), source.getExternalMetadataURI(), getURI());
		};
	}

	protected EntryImpl copyEntryHere(EntryImpl entryToCopy) {
		return this._copyEntryHere(entryToCopy, true);
	}

	private EntryImpl _copyEntryHere(EntryImpl entryToCopy, boolean first) {
		EntryImpl newEntry = null;
		try {
			GraphType bt = entryToCopy.getGraphType();
			if (bt == GraphType.User || bt == GraphType.Context || bt == GraphType.Group || bt == GraphType.SystemContext) {
				return null;
			}

			newEntry = createCopyHere(entryToCopy);
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
		HashMap<IRI, IRI> map = new HashMap<>();
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
		if (obj instanceof RDFResource resource && !(obj instanceof List)) {
			((RDFResource) dest.getResource()).setGraph(replaceURI(resource.getGraph(), source.getSesameResourceURI(), dest.getSesameResourceURI()));
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
				subj = map.get(subj);
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

		if (orderedSetRequirement) {
			HashSet<URI> set = new HashSet<>(newChildren);
			if (set.size() < newChildren.size()) {
				throw new org.entrystore.repository.RepositoryException("Cannot set the list since some of its children occur multiple times.");
			}
		}

		try {
			synchronized (this.entry.repository) {
				// The diff and the validation that depends on it run under the monitor that commits them. Computed
				// outside, two concurrent writers diff the same pre-state and the loser rewrites the member graph
				// without calling removeReferringList for what the winner added, orphaning a referredIn statement.
				Vector<URI> oldChildrenList = loadChildren();
				java.util.List<URI> toRemove = new java.util.ArrayList<>(oldChildrenList);
				toRemove.removeAll(newChildren);
				java.util.List<URI> toAdd = new java.util.ArrayList<>(newChildren);
				toAdd.removeAll(oldChildrenList);

				for (URI uri : toAdd) {
					EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
					if (childEntry == null) {
						throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child " + uri + " does not exist.");
					}
					if (singleParentForListsRequirement
						&& childEntry.getGraphType() == GraphType.List
						&& !childEntry.getReferringListsInSameContext().isEmpty()) {
						throw new org.entrystore.repository.RepositoryException("Cannot set the list since the child " + uri + " is a list which already have a parent.");
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

				boolean committed = false;
				RepositoryConnection rc = entry.repository.getConnection();
				try {
					rc.begin();
					synchronized (childrenLock) {
						children = new Vector<>(newChildren);
					}
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
					committed = true;

					for (EntryImpl updatedChildEntry : updatedChildEntries) {
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(updatedChildEntry, RepositoryEvent.RelationsUpdated));
					}
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					log.error("Failed to set children of list {}", entry.getEntryURI(), e);
					recoverFromFailedMemberWrite(e, committed, () -> {
						synchronized (childrenLock) {
							children = oldChildrenList;
						}
					}, () -> {
						rc.rollback();
						refreshAndPublish(toAdd, rc);
						refreshAndPublish(toRemove, rc);
					});
					throw new org.entrystore.repository.RepositoryException("Cannot set the list since: " + e.getMessage(), e);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to obtain repository connection for entry " + entry.getId(), e);
		}
		return true;
	}

	/** The rollback-and-refresh half of a failed member-list write; separate so it may throw. */
	@FunctionalInterface
	private interface StoreRecovery {
		void run() throws Exception;
	}

	/**
	 * Recovery shared by {@link #setChildren} and {@link #removeChild}, so a fix cannot land in one and miss the
	 * other. Restores the in-memory member list and publishes the group-sourced ResourceUpdated before anything
	 * that can throw: a concurrent authorization scan may already have read the uncommitted list, and the rollback
	 * is exactly what fails on the broken connection that caused the write to fail.
	 * <p>
	 * Once the transaction has committed neither the restore nor the rollback applies — the store holds the change,
	 * so putting the member back would leave the in-memory list the more permissive of the two, and rolling back a
	 * committed transaction fails in its own right. The event is published either way, because the store did change.
	 *
	 * @param committed whether {@code rc.commit()} returned
	 * @param restore puts the in-memory list back as it was; run only when the transaction did not commit
	 * @param recovery rolls back and refreshes the affected members; a failure is logged here and suppressed onto
	 * {@code cause}, which the caller may answer with {@code false} rather than rethrow
	 */
	private void recoverFromFailedMemberWrite(Exception cause, boolean committed, Runnable restore, StoreRecovery recovery) {
		if (!committed) {
			restore.run();
		}
		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
		if (committed) {
			return;
		}
		try {
			recovery.run();
		} catch (Exception recoveryFailure) {
			log.error("Rolling back a failed member-list write on list {} also failed; the transaction may still be "
					+ "open and the affected entries' in-memory relations may be stale", entry.getEntryURI(), recoveryFailure);
			cause.addSuppressed(recoveryFailure);
		}
	}

	/**
	 * Reloads each listed child that still exists from the store and publishes it, so an indexer sees the rolled-back
	 * state; a member whose entry is gone is skipped, as the pre-transaction validation tolerates it.
	 */
	private void refreshAndPublish(Collection<URI> childURIs, RepositoryConnection rc) throws RepositoryException {
		for (URI uri : childURIs) {
			EntryImpl childEntry = (EntryImpl) this.entry.getContext().getByEntryURI(uri);
			if (childEntry != null) {
				childEntry.refreshFromRepository(rc);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
			}
		}
	}

	public java.util.List<URI> getChildren() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.ReadResource);

		Vector<URI> currentChildren = children;
		if (currentChildren == null) {
			currentChildren = loadChildren();
		}
		return Collections.unmodifiableList(currentChildren);
	}

	public void moveChildAfter(URI child, URI afterChild) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);

		synchronized (this.entry.repository) {
			Vector<URI> currentChildren = loadChildren();
			currentChildren.remove(child);
			currentChildren.add(currentChildren.indexOf(afterChild) + 1, child);
			saveChildren();
		}
	}

	public void moveChildBefore(URI child, URI beforeChild) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);

		synchronized (this.entry.repository) {
			Vector<URI> currentChildren = loadChildren();
			currentChildren.remove(child);
			currentChildren.add(currentChildren.indexOf(beforeChild), child);
			saveChildren();
		}
	}

	public boolean removeChild(URI child) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(this.entry, AccessProperty.WriteResource);
		return removeChild(child, true);
	}

	protected boolean removeChild(URI child, boolean checkOrphaned) {

		if (!loadChildren().contains(child)) {
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
			if (!canRemove(checkOrphaned, childEntry, isOwnerOfContext)) {
				return false;
			}
			// The contains check above ran outside the monitor; a concurrent write may have replaced the list since.
			Vector<URI> currentChildren = loadChildren();
			int index = currentChildren.indexOf(child);
			if (index < 0) {
				return false;
			}
			boolean removedInMemory = false;
			boolean committed = false;
			try {
				RepositoryConnection rc = entry.repository.getConnection();
				ValueFactory vf = entry.repository.getValueFactory();
				try {
					rc.begin();
					currentChildren.remove(child);
					removedInMemory = true;
					if (checkOrphaned && isOwnerOfContext) {
						childEntry.setOriginalListSynchronized(null, rc, vf); //remains to do the same for list case.
					}
					saveChildren(rc);
					childEntry.removeReferringList(this, rc);
					rc.commit();
					committed = true;
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
				} catch (Exception e) {
					log.error("Failed to remove child {} from list {}", child, entry.getEntryURI(), e);
					boolean restorable = removedInMemory;
					recoverFromFailedMemberWrite(e, committed, () -> {
						if (restorable) {
							synchronized (childrenLock) {
								currentChildren.add(index, child);
							}
						}
					}, () -> {
						rc.rollback();
						childEntry.refreshFromRepository(rc);
					});
					return false;
				} finally {
					rc.close();
				}
			} catch (RepositoryException e) {
				// Also reached when a post-commit rc.close() fails, which does not undo the removal.
				log.error("Failed to obtain or close the repository connection for list {}", entry.getEntryURI(), e);
				return committed;
			}
			return true;
		}
	}

	/**
	 * Removes several children from this list as part of the supplied transaction — one graph read and one
	 * rewrite for the whole batch — without firing repository events or checking for orphans. Reads and
	 * writes go through the supplied connection only, so earlier uncommitted removals in the same transaction
	 * are seen and nothing uncommitted is published to concurrent readers; the in-memory members are cleared so
	 * they are reloaded on next access whatever the transaction outcome. The list entry's modification date
	 * and contributors are updated in memory, so after a rollback the caller must refresh it. No repository event
	 * is fired here either.
	 * <p>
	 * Clearing them here is not on its own enough, so after the commit the caller must call
	 * {@link #invalidateChildren()} and only then publish the list's change ({@code importContext} does both per
	 * pruned list). A reader that does not hold the transaction can reload the members from the pre-commit store
	 * at any point while it is open, which puts them back; the user-to-groups cache would otherwise re-scan that
	 * reloaded list as authoritative once the event has bumped its epoch.
	 */
	protected void removeChildrenInTransaction(Collection<URI> childrenToRemove, RepositoryConnection rc) throws RepositoryException {
		synchronized (this.entry.repository) {
			Model graph = new LinkedHashModel(Iterations.asList(rc.getStatements(this.resourceURI, null, null, false, this.resourceURI)));
			Vector<URI> inTransactionChildren = loadChildren(graph);
			if (inTransactionChildren.removeAll(childrenToRemove)) {
				saveChildren(inTransactionChildren, rc);
			}
			synchronized (childrenLock) {
				children = null;
			}
		}
	}

	/**
	 * Drops the in-memory member list so the next read reloads it from the committed store. For callers that changed
	 * the list through their own transaction: a concurrent reader may hold, or be in the middle of loading, the
	 * pre-commit list, and that copy must not outlive the commit. Takes {@link #childrenLock}, the same lock
	 * {@link #loadChildren()} holds across its load, so a reader that is loading publishes its copy before this
	 * drops it rather than after.
	 */
	void invalidateChildren() {
		synchronized (childrenLock) {
			children = null;
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
			Vector<URI> currentChildren = loadChildren();
			rc.clear(this.resourceURI);
			for (URI uri : currentChildren) {
				EntryImpl childEntry = ((EntryImpl) this.entry.getContext().getByEntryURI(uri));
				childEntry.removeReferringList(this, rc);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(childEntry, RepositoryEvent.EntryUpdated));
			}
			synchronized (childrenLock) {
				children = null;
			}
			entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceDeleted));
		}
	}

	public void removeTree() {
		Context c = this.entry.getContext();
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(c.getEntry(), AccessProperty.Administer);
		java.util.List<URI> tchildren = new ArrayList<>(loadChildren());
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
		// snapshot: this loop writes six triples per child and recurses, so iterating the live vector would
		// let a concurrent member change abort it part-applied with no rollback
		for (URI uri : new ArrayList<>(loadChildren())) {
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
