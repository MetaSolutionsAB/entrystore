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

import lombok.Getter;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.AuthorizationException;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConfigurationException;
import java.net.URI;


public class MetadataImpl implements Metadata {

	private EntryImpl entry;
	private IRI uri;
	private IRI resourceUri;
	private org.eclipse.rdf4j.model.Resource mdContext;
	@Getter
	private boolean cached;
	private boolean localCache;
	/**
	 * True when the {@link #mdContext} named graph is known to contain no statements <em>because
	 * this object put it in that state</em>. Set by {@link EntryImpl#create} for freshly created
	 * entries (the only path where the mdContext is guaranteed empty) and maintained by
	 * {@link #doSetGraph}. When true, the overwrite half of {@code doSetGraph} can skip its read +
	 * clear + inverse-relation loop entirely. Volatile because creation and the first
	 * {@code setGraph} can land on different threads.
	 *
	 * <p>{@code doSetGraph} drops the claim before it writes and restores it only once the write is
	 * durable — after its own commit, or, inside a batch where its statements are merely staged,
	 * from {@link RepositoryManagerImpl#runAfterBatchCommit}. A rolled-back write therefore leaves
	 * the flag false rather than describing a graph the rollback put back. That costs a batch its
	 * short-circuit on a second write to the same entry, which is the correct trade: the first
	 * write's statements really are on the connection and have to be cleared.
	 *
	 * <p>{@link #markKnownEmpty} is the one exception and needs none of this. It fires from
	 * {@link EntryImpl#create}, so if that transaction rolls back the entry never existed and its
	 * mdContext is empty either way — which is what the flag says.
	 *
	 * <p>Deliberately <b>not</b> consulted by {@link #removeGraphSynchronized}; see
	 * {@link #removeGraphSynchronized(RepositoryConnection, boolean)} for why the deletion path
	 * cannot trust it.
	 */
	private volatile boolean knownEmpty = false;
	Logger log = LoggerFactory.getLogger(MetadataImpl.class);

	public MetadataImpl(EntryImpl entry, IRI uri, IRI resourceUri, boolean cached) {
		this.entry = entry;
		this.uri = uri;
		this.resourceUri = resourceUri;
		this.mdContext = uri;
		this.cached = cached;
		this.localCache = true; //TODO fix
	}

	/**
	 * Package-private hint that the {@link #mdContext} is currently empty in the
	 * repository. Called by {@link EntryImpl#create} for freshly created entries
	 * so the first {@link #setGraph} can short-circuit its overwrite.
	 */
	void markKnownEmpty() {
		this.knownEmpty = true;
	}

	public Model getGraph() {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadMetadata);
		}
		/*		if (cached && localCache) {
			Entry cachedFrom = this.entry.getRepositoryManager().getContextManager().getEntry(getURI());
			if (cachedFrom != null) {
				return cachedFrom.getMetadataGraph();
			}
		}*/
		RepositoryConnection rc = null;
		try {
			rc = this.entry.repository.getConnection();
			return Iterations.addAll(rc.getStatements(null, null, null, false, mdContext), new LinkedHashModel());
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		} finally {
			try {
				rc.close();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			} 
		}
	}

	public URI getURI() {
		if (this.uri != null) {
			return URI.create(this.uri.toString());
		} else {
			log.warn("Metadata URI is null of entry: " + this.entry.getEntryURI());
			return null;
		}
	}

	public URI getResourceURI() {
		return URI.create(resourceUri.toString());
	}

	public void setGraph(Model graph) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteMetadata);
		}

		try {
			synchronized (this.entry.repository) {
				RepositoryConnection batchRc = this.entry.repositoryManager.getActiveBatchConnection();
				if (batchRc != null) {
					doSetGraph(batchRc, graph, false);
					return;
				}
				RepositoryConnection rc = this.entry.repository.getConnection();
				try {
					doSetGraph(rc, graph, true);
				} finally {
					rc.close();
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}

	private void doSetGraph(RepositoryConnection rc, Model graph, boolean manageTx) {
		if (manageTx) {
			rc.begin();
		}
		// Read once and drop the claim before touching the store. From here until the write is
		// durable this object cannot say what mdContext holds, and every way out of this method
		// short of a commit — a rollback below, a batch that rolls back later — leaves the store
		// holding the pre-write graph. Leaving the flag set across such a failure would let the
		// next setGraph skip its overwrite and merge onto data it believes is not there.
		boolean wasKnownEmpty = knownEmpty;
		knownEmpty = false;
		boolean writingEmptyGraph = graph == null || graph.isEmpty();
		try {
			Model oldGraph = removeGraphSynchronized(rc, wasKnownEmpty);
			addGraphSynchronized(rc, graph);
			ProvenanceImpl provenance = (ProvenanceImpl) this.entry.getProvenance();
			if (provenance != null && !cached) {
				provenance.addMetadataEntity(oldGraph, rc);
			}
			if (manageTx) {
				rc.commit();
				knownEmpty = writingEmptyGraph;
			} else {
				// Inside a batch the statements above are only staged, so the claim is published
				// once the batch commits — and dropped with the batch if it does not. Registered
				// for every write, empty or not, because the actions run in registration order:
				// the last write in the batch is therefore the one that has the final say.
				entry.repositoryManager.runAfterBatchCommit(() -> knownEmpty = writingEmptyGraph);
			}
			if (cached) {
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ExternalMetadataUpdated, graph));
			} else {
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.MetadataUpdated, graph));
			}
		} catch (AuthorizationException ae) {
			if (manageTx) {
				rc.rollback();
			}
			log.warn(ae.getMessage());
			throw ae;
		} catch (Exception e) {
			if (manageTx) {
				rc.rollback();
			}
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
		}
	}
	public Model removeGraphSynchronized(RepositoryConnection rc) throws RepositoryException {
		return removeGraphSynchronized(rc, false);
	}

	/**
	 * @param graphIsKnownEmpty whether {@link #mdContext} is already known to hold nothing, in which
	 *                          case there is nothing to read, clear or un-relate. Only
	 *                          {@link #doSetGraph} ever passes true, from {@link #knownEmpty}: it is
	 *                          about to overwrite the graph this object itself last wrote, and
	 *                          skipping the read there is what makes bulk creation viable
	 *                          (ENTRYSTORE-1074).
	 *                          <p>
	 *                          Entry deletion passes false unconditionally. {@code knownEmpty} only
	 *                          tracks writes that went through this instance, so it says nothing
	 *                          about statements the graph acquired some other way — a restore, a
	 *                          repair, a direct store write — and deletion's job is to leave none of
	 *                          those behind. A skipped clear there orphans them permanently behind an
	 *                          entry that no longer exists, and deletion is not a hot path, so it
	 *                          pays the scan.
	 */
	private Model removeGraphSynchronized(RepositoryConnection rc, boolean graphIsKnownEmpty)
			throws RepositoryException {
		if (graphIsKnownEmpty) {
			// mdContext is known empty: nothing to read, nothing to clear, no inverse relations to remove.
			return new LinkedHashModel();
		}
		String base = this.entry.repositoryManager.getRepositoryURL().toString();
		//Fetch old graph
		Model graph = Iterations.addAll(rc.getStatements(null, null, null, false, mdContext), new LinkedHashModel());

		// Remove relations in other entries inverse relational cache if entry has repository URL.
		if (this.resourceUri.stringValue().startsWith(base)) { //Only check for relations for non external links at this point.
			for (Statement statement : graph) {
				Value obj = statement.getObject();
				Resource subj = statement.getSubject();
				//Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
				if (obj instanceof IRI
						&& obj.stringValue().startsWith(base)
						&& subj.stringValue().startsWith(base)) {
					URI entryURI = URI.create(statement.getObject().stringValue());

					EntryImpl sourceEntry = (EntryImpl) this.entry.getRepositoryManager().getContextManager().getEntry(entryURI);
					if (sourceEntry != null) {
						sourceEntry.removeRelationSynchronized(statement, rc);
					}
				}
			}
		}
		rc.clear(mdContext);
		return graph;
	}
	
	public void addGraphSynchronized(RepositoryConnection rc, Model graph) throws RepositoryException, DatatypeConfigurationException {
		String base = this.entry.repositoryManager.getRepositoryURL().toString();

		rc.add(graph, mdContext);
		if (cached) {
			((EntryImpl) this.entry).updateCachedExternalMetadataDateSynchronized(rc, this.entry.repository.getValueFactory());
		} else {
			((EntryImpl) this.entry).updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
		}

		// Check if there are any relations in the metadata graph.
		// If it is, then add them to the source entry's relation graph.
		//Old graph, remove from target entry relation index.
		if (this.resourceUri.stringValue().startsWith(base)) { //Only check for relations for non external links at this point.
			for (Statement statement : graph) {
				Value obj = statement.getObject();
				Resource subj = statement.getSubject();
				//Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
				if (obj instanceof IRI
						&& obj.stringValue().startsWith(base)
						&& subj.stringValue().startsWith(base)) {
					URI entryURI = URI.create(statement.getObject().stringValue());

					// we fetch the entry without respecting the ACL (in case the modifying user lacks read access), otherwise we
					// can't update the inverse relational cache and the whole operation would fail
					EntryImpl sourceEntry = (EntryImpl) ((ContextManagerImpl) this.entry.getRepositoryManager().getContextManager()).getEntryIgnoreACL(entryURI);
					if (sourceEntry != null) {
						sourceEntry.addRelationSynchronized(statement, rc);
					}
				}
			}
		}
	}

}
