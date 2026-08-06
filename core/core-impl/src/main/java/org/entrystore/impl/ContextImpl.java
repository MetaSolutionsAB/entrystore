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

import lombok.Getter;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
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
import org.entrystore.exception.EntryMissingException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.security.DisallowedException;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.URISplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;

public class ContextImpl extends ResourceImpl implements Context {

	private long counter = -1;
	@Getter
	protected final SoftCache softCache;
	protected String id;
	/**
	 * External-metadata-URI and resource-URI indexes, mapping a URI to either a single entry URI or a
	 * {@code Set} of them.
	 *
	 * <p>Concurrency (ENTRYSTORE-1095). Four properties are load-bearing:
	 *
	 * <ul>
	 * <li><b>volatile</b>, and {@link #loadIndex()} assigns the field only once the map is fully
	 * populated. Assigning an empty map first and filling it afterwards let a reader observe a
	 * non-null field, skip {@code loadIndex()} — and so never contend for its lock — and then iterate
	 * a map still being written.</li>
	 * <li><b>Concurrent maps, with concurrent value sets.</b> Readers hold no monitor, so weakly
	 * consistent iteration at both levels is what makes a listing safe against a concurrent entry
	 * creation or removal instead of throwing {@code ConcurrentModificationException}.</li>
	 * <li><b>Read once into a local</b> before use. {@link #reIndex()} nulls these fields, so reading
	 * the field again after the null check can dereference null, and a {@code pop} followed by a
	 * {@code push} that each re-read it can land on two different maps.</li>
	 * <li><b>No write is ever dropped.</b> A writer that finds the fields unpublished records its
	 * operation in {@link #pendingIndexOps} instead of skipping it; see {@link #applyIndexOp}.</li>
	 * </ul>
	 *
	 * <p><b>Lock protocol.</b> {@link #indexLock} is a per-context monitor guarding the publication of
	 * these two fields and the pending-operation list. It is the only monitor any index path takes:
	 *
	 * <ul>
	 * <li>writers hold {@code entry.repository} already and acquire {@code indexLock} inside it, so the
	 * order is {@code repository} → {@code indexLock} wherever both are held;</li>
	 * <li>{@link #loadIndex()} scans holding <em>nothing</em>, and acquires only {@code indexLock} to
	 * publish. It deliberately does not take {@code entry.repository}: that is a single store-wide
	 * instance, so holding it across a full context scan stalls every write in every context — the
	 * reason {@code 45cdc777} moved off it in 2020;</li>
	 * <li>{@link #reIndex()} clears both fields under {@code indexLock}.</li>
	 * </ul>
	 *
	 * There is therefore no lock cycle and no path on which a reader waits for a writer.
	 *
	 * <p>What this does and does not guarantee: reads never throw
	 * {@code ConcurrentModificationException}, each single-key {@code push}/{@code pop} is atomic, and no
	 * committed write is missing from a published index. It does <em>not</em> make a read plus a
	 * dependent write atomic, so a listing can be missing an entry that a concurrent
	 * {@code updateResource2EntryIndex} has popped but not yet pushed. Callers needing more must
	 * coordinate. {@code remove(RepositoryConnection)} is the one caller that genuinely needed to, and it
	 * no longer reads these indexes to decide what to delete.
	 */
	private volatile ConcurrentMap<URI, Object> extMdUri2entry;
	private volatile ConcurrentMap<URI, Object> res2entry;

	/** Guards publication of the two index fields and {@link #pendingIndexOps}. See their javadoc. */
	private final Object indexLock = new Object();

	/**
	 * Index writes that arrived while the indexes were unpublished, replayed onto the freshly scanned
	 * maps by {@link #loadIndex()} before it publishes them.
	 *
	 * <p>This is what makes publish-at-end safe without holding the writers' monitor across the scan.
	 * {@code addToIndex} runs <em>inside</em> the creating transaction — {@code createNewMinimalItem}
	 * commits several lines later — so a writer that simply skipped its push would have its
	 * {@code resHasEntry} triple invisible both to the index (skipped) and to a scan that finished
	 * before the commit, losing the mapping for the life of this object. Neither a write counter nor a
	 * mutex around the index touch closes that: the reader can read the counter after the writer bumped
	 * it and still publish before the commit lands. Recording the operation does close it, because the
	 * publish replays whatever the scan could not see.
	 *
	 * <p>Only populated between construction (or a {@link #reIndex()}) and the first read, so it is
	 * empty in steady state. {@link #INDEX_OP_BUFFER_LIMIT} bounds a pathological case — a bulk import
	 * into a context nobody reads — after which {@link #pendingIndexOpsOverflowed} makes the next load
	 * take the pessimistic path instead of growing without limit.
	 */
	private final List<IndexOp> pendingIndexOps = new ArrayList<>();
	private boolean pendingIndexOpsOverflowed;

	/** Cap on {@link #pendingIndexOps}; see its javadoc. */
	private static final int INDEX_OP_BUFFER_LIMIT = 10_000;

	/**
	 * True when the published index is missing at least one statement that could not be indexed.
	 *
	 * <p>A field rather than a local count inside the load, because the consequences outlive the load.
	 * Listings from this context are short, and three consumers act on a short listing destructively
	 * rather than merely incompletely: the Solr reindex purge deletes every unlisted entry from the
	 * search index, {@code recalculateQuotaFillLevel}'s total is persisted, and
	 * {@code ContextManagerImpl.importContext}'s purge loop keeps unlisted entries alive across a
	 * replace-import. Each of those checks {@link #isIndexComplete()} and refuses instead. Assigned
	 * unconditionally on every publish, so repairing the data and re-reading clears it.
	 */
	private volatile boolean indexIncomplete;

	/** Which index an {@link IndexOp} applies to. */
	private enum IndexKind {RESOURCE, EXTERNAL_METADATA}

	/**
	 * A deferred {@code push} or {@code pop}. {@code to} being null means "remove {@code from}'s mapping
	 * to {@code entryURI}"; the two are otherwise identical in shape.
	 */
	private record IndexOp(IndexKind kind, boolean push, URI from, URI to) {}
	protected ArrayList<URI> systemEntries = new ArrayList<>();

	private static final Logger log = LoggerFactory.getLogger(ContextImpl.class);

	public static final IRI DCModified;
	public static final IRI DCTermsModified;

	private final Object quotaMutex = new Object();
	protected long quotaFillLevel = Quota.VALUE_UNCACHED;
	protected long quota = Quota.VALUE_UNCACHED;

	@Getter
	private volatile boolean deleted = false;

	static {
		DCModified = iri(NS.dc, "modified");
		DCTermsModified = iri(NS.dcterms, "modified");
	}

	protected ContextImpl(EntryImpl entry, String uri, SoftCache softCache) {
		super(entry, uri);
		this.softCache = softCache;
		this.id = uri.substring(uri.lastIndexOf('/') + 1);
	}

	public ContextImpl(EntryImpl entry, IRI contextUri, SoftCache softCache) {
		super(entry, contextUri);
		this.softCache = softCache;
		this.id = resourceURI.toString().substring(resourceURI.toString().lastIndexOf('/') + 1);
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
					rc.begin();

					// delete old index
					rc.remove((Resource) null, RepositoryProperties.mdHasEntry, null, this.resourceURI);
					rc.remove((Resource) null, RepositoryProperties.resHasEntry, null, this.resourceURI);
					rc.remove((Resource) null, RepositoryProperties.counter, null, this.resourceURI);

					List<Statement> stmntsToAdd = new ArrayList<>();

					// create a new index by finding all sesame contexts that belong to this context
					int maxIndex = 0;
					RepositoryResult<Statement> resources = rc.getStatements(null, RepositoryProperties.resource, null, false);
					while (resources.hasNext()) {
						Statement statement = resources.next();
						Resource mmd = statement.getContext();
						if (mmd instanceof IRI rI) {
							if (!mmd.stringValue().startsWith(entry.getRepositoryManager().getRepositoryURL().toString())) {
								log.warn("This Entry URI does not belong to this repository: {}", mmd.stringValue());
								continue;
							}

							StringTokenizer tokenizer = Util.extractParameters(entry.repositoryManager, rI);
							if (tokenizer.countTokens() == 3 && tokenizer.nextToken().equals(this.id)) { // Belongs to this context.
								try {
									tokenizer.nextToken(); //Ignoring the M
									int index = Integer.parseInt(tokenizer.nextToken());
									if (index > maxIndex) {
										maxIndex = index;
									}
								} catch (NumberFormatException nfe) {
								}
								// this does not work: addToIndex((org.openrdf.model.URI) statement.getSubject(),(org.openrdf.model.URI) statement.getObject(),(org.openrdf.model.URI) statement.getContext(), rc);
								stmntsToAdd.add(vf.createStatement((Resource) statement.getObject(), RepositoryProperties.resHasEntry, statement.getContext(), this.resourceURI));
							}
						}
					}
					resources.close();

					RepositoryResult<Statement> externalMD = rc.getStatements(null, RepositoryProperties.externalMetadata, null, false);
					while (externalMD.hasNext()) {
						Statement statement = externalMD.next();
						Resource mmd = statement.getContext();
						if (mmd instanceof IRI rI1) {
							StringTokenizer stok = Util.extractParameters(entry.repositoryManager, rI1);
							if (stok.countTokens() == 3 && stok.nextToken().equals(this.id)) { //Belongs to this context.
								stmntsToAdd.add(vf.createStatement((Resource) statement.getObject(), RepositoryProperties.mdHasEntry, statement.getContext(), this.resourceURI));
							}
						}
					}
					externalMD.close();

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

		// Cleared under the same monitor loadIndexes() publishes under, and both together, so no reader
		// ever observes a mismatched pair of one freshly published index and one null. Any write that
		// arrives between this and the reload is recorded by applyIndexOp and replayed, so the rebuild
		// cannot drop it (ENTRYSTORE-1095).
		synchronized (indexLock) {
			this.res2entry = null;
			this.extMdUri2entry = null;
		}

		loadIndex();
	}

	/**
	 * Adds a {@code from -> to} mapping, promoting a single value to a set when a second one arrives.
	 *
	 * <p>Done inside {@code compute} so the read-modify-write is atomic per key: the previous
	 * get-then-put let two concurrent writers on the same key lose one of the two mappings.
	 *
	 * <p>The value stays the {@code URI}-or-{@code Set<URI>} union these indexes have always stored,
	 * rather than always a set, which would drop the cast below and the one in
	 * {@link #addEntryURIs(Object, Collection)}. {@code push} and {@code pop} are the only writers, so
	 * the union stays closed to those two types.
	 */
	@SuppressWarnings("unchecked")
	private void push(URI from, URI to, ConcurrentMap<URI, Object> map) {
		if (from == null || to == null) {
			return;
		}
		map.compute(from, (key, existingTo) -> {
			if (existingTo == null) {
				return to;
			}
			if (existingTo instanceof Set) {
				((Set<URI>) existingTo).add(to);
				return existingTo;
			}
			// Concurrent, because getEntries and getByResourceURI iterate these sets unsynchronized.
			Set<URI> set = ConcurrentHashMap.newKeySet();
			set.add((URI) existingTo);
			set.add(to);
			return set;
		});
	}

	/**
	 * Removes a {@code from -> to} mapping, dropping the key once its last value is gone. Returning
	 * null from {@code compute} removes the entry, which is how the previous {@code map.remove(from)}
	 * behaviour is preserved atomically.
	 */
	private void pop(URI from, URI to, ConcurrentMap<URI, Object> map) {
		if (from == null || to == null) {
			return;
		}
		map.compute(from, (key, existingTo) -> {
			if (existingTo == null) {
				return null;
			}
			if (existingTo instanceof Set<?> set) {
				set.remove(to);
				return set.isEmpty() ? null : set;
			}
			return existingTo.equals(to) ? null : existingTo;
		});
	}

	void updateResource2EntryIndex(URI oldResourceURI, URI newResourceURI, URI entryURI) {
		if (oldResourceURI == null || newResourceURI == null || entryURI == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		// Two operations, each atomic on its own key, so a reader can still observe the gap between them —
		// stated in the field javadoc rather than fixed here, because the one caller that could not
		// tolerate it, remove(RepositoryConnection), no longer reads these indexes at all.
		log.debug("Removing resource to entry mapping: {} -> {}", oldResourceURI, entryURI);
		applyIndexOp(IndexKind.RESOURCE, false, oldResourceURI, entryURI);
		log.debug("Adding resource to entry mapping: {} -> {}", newResourceURI, entryURI);
		applyIndexOp(IndexKind.RESOURCE, true, newResourceURI, entryURI);
	}

	void updateExternalMetadata2EntryIndex(URI oldExtMdURI, URI newExtMdURI, URI entryURI) {
		if (oldExtMdURI == null || newExtMdURI == null || entryURI == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		log.debug("Removing external metadata to entry mapping: {} -> {}", oldExtMdURI, entryURI);
		applyIndexOp(IndexKind.EXTERNAL_METADATA, false, oldExtMdURI, entryURI);
		log.debug("Adding external metadata to entry mapping: {} -> {}", newExtMdURI, entryURI);
		applyIndexOp(IndexKind.EXTERNAL_METADATA, true, newExtMdURI, entryURI);
	}

	/**
	 * The two published indexes, so a caller that had to load them does not have to re-read the volatile
	 * fields — and so cannot observe a {@link #reIndex()} nulling them in between.
	 */
	private record Indexes(ConcurrentMap<URI, Object> resource, ConcurrentMap<URI, Object> externalMetadata) {}

	void loadIndex() {
		loadIndexes();
	}

	/**
	 * Scans this context's graph and publishes both indexes, or returns the already-published pair.
	 *
	 * <p>The scan holds <b>no monitor</b>. Publication happens under {@link #indexLock}, and any write
	 * that arrived while the indexes were unpublished is replayed from {@link #pendingIndexOps} onto the
	 * scanned maps first — which is what makes it safe not to hold the writers' monitor here. See
	 * {@link #pendingIndexOps} for why a write counter or a mutex around the index touch would not be
	 * enough, and {@link #res2entry} for the lock protocol.
	 *
	 * <p>One case still takes {@code entry.repository}: if the pending buffer overflowed, there is no
	 * record of what was dropped, so this falls back to excluding writers for the duration of one scan.
	 * That needs {@link #INDEX_OP_BUFFER_LIMIT} un-read writes to a single context to happen at all.
	 */
	private Indexes loadIndexes() {
		Indexes published = publishedIndexes();
		if (published != null) {
			return published;
		}
		boolean pessimistic;
		synchronized (indexLock) {
			pessimistic = pendingIndexOpsOverflowed;
		}
		if (pessimistic) {
			synchronized (this.entry.repository) {
				return scanAndPublish();
			}
		}
		return scanAndPublish();
	}

	private Indexes scanAndPublish() {
		// Built locally and published only at the end. Assigning the fields up front and filling them
		// afterwards let an unsynchronized reader see a non-null field, skip loading entirely — never
		// contending for the lock — and iterate a half-built map (ENTRYSTORE-1095).
		ConcurrentMap<URI, Object> newResIndex = new ConcurrentHashMap<>();
		ConcurrentMap<URI, Object> newExtMdIndex = new ConcurrentHashMap<>();
		int failedStatements = 0;
		long scannedCounter = -1;
		try (RepositoryConnection rc = entry.repository.getConnection()) {
			RepositoryResult<Statement> statements = rc.getStatements(null, null, null, false, this.resourceURI);
			while (statements.hasNext()) {
				Statement statement = statements.next();
				try {
					IRI predicate = statement.getPredicate();
					if (predicate.equals(RepositoryProperties.mdHasEntry)) {
						URI mdURI = URI.create(statement.getSubject().toString());
						URI entryURI = URI.create(statement.getObject().toString());
						push(mdURI, entryURI, newExtMdIndex);
					} else if (predicate.equals(RepositoryProperties.resHasEntry)) {
						URI resourceURI = URI.create(statement.getSubject().toString());
						URI entryURI = URI.create(statement.getObject().toString());
						push(resourceURI, entryURI, newResIndex);
					} else if (predicate.equals(RepositoryProperties.counter)) {
						scannedCounter = ((Literal) statement.getObject()).intValue();
					}
				} catch (Exception e) {
					// Logged and skipped, not fatal. Refusing to publish would leave both fields null for
					// the life of the object, so every later read would rescan the whole graph and fail
					// again, reIndex() could not repair it (it ends in this method), and
					// ContextManagerImpl's constructor would turn one bad triple in the _contexts graph
					// into a startup failure. ENTRYSTORE-839 established this tolerance deliberately, and
					// remove(RepositoryConnection) reads its child list from the repository rather than
					// from this index, so an incomplete index cannot orphan data.
					//
					// It can still mislead, which is what indexIncomplete below exists for: an
					// unresolvable principal is fail-open in PrincipalManagerImpl.hasAccess, and the Solr
					// purge, the persisted quota fill level and importContext's purge loop all act
					// destructively on a listing. Those consumers check isIndexComplete().
					failedStatements++;
					log.error("Could not index statement {} in context {}: {}",
							statement, this.resourceURI, e.getMessage(), e);
				}
			}
			statements.close();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
		if (failedStatements > 0) {
			log.error("{} statement(s) in context {} could not be indexed; listings from this context will "
							+ "be incomplete, and operations that delete or persist totals derived from them "
							+ "are refused, until the underlying data is repaired",
					failedStatements, this.id);
		}

		synchronized (indexLock) {
			// Both fields, not just res2entry: guarding on one alone made a half-cleared pair
			// unrecoverable, because this early return would then skip rebuilding the other.
			Indexes concurrentlyPublished = publishedIndexes();
			if (concurrentlyPublished != null) {
				// Another loader won the race; discard this scan rather than replacing a live index.
				return concurrentlyPublished;
			}
			for (IndexOp op : pendingIndexOps) {
				ConcurrentMap<URI, Object> target =
						op.kind() == IndexKind.RESOURCE ? newResIndex : newExtMdIndex;
				if (op.push()) {
					push(op.from(), op.to(), target);
				} else {
					pop(op.from(), op.to(), target);
				}
			}
			pendingIndexOps.clear();
			pendingIndexOpsOverflowed = false;
			// Assigned unconditionally, so a later clean load — or a reIndex() that repairs the data —
			// clears the flag rather than leaving the context marked incomplete for good.
			this.indexIncomplete = failedStatements > 0;
			if (scannedCounter > -1) {
				this.counter = scannedCounter;
			}
			extMdUri2entry = newExtMdIndex;
			res2entry = newResIndex;
			return new Indexes(newResIndex, newExtMdIndex);
		}
	}

	/** The published pair, or null while either field is unpublished. */
	private Indexes publishedIndexes() {
		ConcurrentMap<URI, Object> resIndex = res2entry;
		ConcurrentMap<URI, Object> extMdIndex = extMdUri2entry;
		return resIndex != null && extMdIndex != null ? new Indexes(resIndex, extMdIndex) : null;
	}

	/**
	 * Applies one index write, or records it for {@link #loadIndexes()} to replay when the indexes are
	 * not published yet. Called with {@code entry.repository} already held by the write paths, so the
	 * lock order is {@code repository} → {@code indexLock}.
	 */
	private void applyIndexOp(IndexKind kind, boolean isPush, URI from, URI to) {
		if (from == null || to == null) {
			return;
		}
		synchronized (indexLock) {
			ConcurrentMap<URI, Object> index = kind == IndexKind.RESOURCE ? res2entry : extMdUri2entry;
			if (index != null) {
				if (isPush) {
					push(from, to, index);
				} else {
					pop(from, to, index);
				}
				return;
			}
			if (pendingIndexOps.size() >= INDEX_OP_BUFFER_LIMIT) {
				// Give up on replay rather than growing without limit. The next load excludes writers for
				// one scan instead, which is correct — just coarser.
				if (!pendingIndexOpsOverflowed) {
					log.warn("More than {} index writes to context {} before it was ever read; the next "
									+ "index load will exclude writers for the duration of its scan",
							INDEX_OP_BUFFER_LIMIT, this.id);
				}
				pendingIndexOpsOverflowed = true;
				pendingIndexOps.clear();
				return;
			}
			pendingIndexOps.add(new IndexOp(kind, isPush, from, to));
		}
	}

	private void addToIndex(IRI entryURI, IRI resURI, IRI extMdURI, RepositoryConnection rc) throws RepositoryException {
		rc.add(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);
		URI euri = URI.create(entryURI.toString());

		// applyIndexOp, not a direct push into a field read here: this runs inside the creating
		// transaction, which commits later, so if the indexes are not published yet the mapping has to be
		// recorded rather than skipped — a scan that finishes before that commit cannot see the triple.
		if (extMdURI != null) {
			rc.add(extMdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			applyIndexOp(IndexKind.EXTERNAL_METADATA, true, URI.create(extMdURI.toString()), euri);
		}
		applyIndexOp(IndexKind.RESOURCE, true, URI.create(resURI.toString()), euri);
	}

	protected void removeFromIndex(EntryImpl entry, RepositoryConnection rc) throws RepositoryException {
		IRI entryURI = entry.getSesameEntryURI();
		IRI resURI = entry.getSesameResourceURI();
		IRI mdURI = entry.getSesameExternalMetadataURI();

		rc.remove(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);

		// Recorded rather than skipped when the indexes are unpublished; see addToIndex.
		if (mdURI != null) {
			rc.remove(mdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			applyIndexOp(IndexKind.EXTERNAL_METADATA, false,
					entry.getExternalMetadataURI(), entry.getEntryURI());
		}
		applyIndexOp(IndexKind.RESOURCE, false, entry.getResourceURI(), entry.getEntryURI());

		if (RepositoryManagerImpl.trackDeletedEntries) {
			// add deletion information to index
			ValueFactory vf = rc.getValueFactory();
			XMLGregorianCalendar deletedDate = null;
			try {
				deletedDate = DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar());
			} catch (DatatypeConfigurationException e) {
				log.error(e.getMessage());
			}
			if (deletedDate != null) {
				Statement delDateStatement = vf.createStatement(entryURI, RepositoryProperties.Deleted, vf.createLiteral(deletedDate), this.resourceURI);
				rc.add(delDateStatement, this.resourceURI);
			}
			URI deletedBy = entry.getRepositoryManager().getPrincipalManager().getAuthenticatedUserURI();
			if (deletedBy != null) {
				Statement delByStatement = vf.createStatement(entryURI, RepositoryProperties.DeletedBy, vf.createIRI(deletedBy.toString()), this.resourceURI);
				rc.add(delByStatement, this.resourceURI);
			}
		}
	}

	public Map<URI, DeletedEntryInfo> getDeletedEntries() {
		RepositoryConnection rc = null;
		List<Statement> delDates = null;
		List<Statement> delPrincipals = null;

		synchronized (this.entry.repository) {
			try {
				rc = entry.getRepository().getConnection();
				delDates = rc.getStatements(null, RepositoryProperties.Deleted, null, false, this.resourceURI).stream().toList();
				delPrincipals = rc.getStatements(null, RepositoryProperties.DeletedBy, null, false, this.resourceURI).stream().toList();
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

		Map<URI, Date> uri2date = new HashMap<>();
		if (delDates != null) {
			for (Statement dateStatement : delDates) {
				URI deletedEntryURI = URI.create(dateStatement.getSubject().stringValue());
				Date deletionDate = ((Literal) dateStatement.getObject()).calendarValue().toGregorianCalendar().getTime();
				uri2date.put(deletedEntryURI, deletionDate);
			}
		}

		Map<URI, URI> uri2principal = new HashMap<>();
		if (delPrincipals != null) {
			for (Statement principalStatement : delPrincipals) {
				URI deletedEntryURI = URI.create(principalStatement.getSubject().stringValue());
				URI deletedBy = URI.create(principalStatement.getObject().stringValue());
				uri2principal.put(deletedEntryURI, deletedBy);
			}
		}

		Map<URI, DeletedEntryInfo> result = new HashMap<>();
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

		Map<URI, DeletedEntryInfo> result = new HashMap<>();
		Map<URI, DeletedEntryInfo> allDeletedEntries = getDeletedEntries();
		for (URI delEntryURI : allDeletedEntries.keySet()) {
			boolean inRange = true;
			DeletedEntryInfo delEntryInfo = allDeletedEntries.get(delEntryURI);
			Date deletionDate = delEntryInfo.getDeletionDate();
			if (deletionDate != null) {
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
			// Factory and connection.
			try (RepositoryConnection rc = entry.repository.getConnection()) {
				ValueFactory vf = entry.repository.getValueFactory();
				rc.begin();

				// Find current counter
				if (counter == -1) {
					List<Statement> counters = rc.getStatements(
						this.resourceURI,
						RepositoryProperties.counter,
						null,
						false,
						this.resourceURI).stream().toList();

					if (!counters.isEmpty()) {
						counter = ((Literal) counters.getFirst().getObject()).intValue();
					} else {
						counter = 0;
					}
				}

				// Find new information identity
				String base = entry.repositoryManager.getRepositoryURL().toString();
				List<Statement> infoRecord;
				String identity;
				if (entryId != null) {
					identity = entryId;
				} else {
					do {
						counter++;
						identity = Long.toString(counter);
						IRI entryUri = vf.createIRI(base + this.id + "/" + RepositoryProperties.ENTRY_PATH + "/" + counter);
						infoRecord = rc.getStatements(null, null, null, false, entryUri).stream().toList();
					} while (!infoRecord.isEmpty()); // keep counting if a candidate is taken
				}

				// resURI - resourceURI
				IRI resURI;
				if (resourceURI != null) {
					String resourceURIStr = resourceURI.toString().replace("_newId", identity);
					resURI = vf.createIRI(resourceURIStr);
				} else {
					if (bType == GraphType.Context ||
						bType == GraphType.SystemContext) {
						resURI = vf.createIRI(URISplit.createURI(base, identity).toString());
					} else {
						resURI = vf.createIRI(URISplit.createURI(base, this.id, RepositoryProperties.getResourcePath(bType), identity).toString());
					}
				}

				EntryImpl newEntry = null;
				try {
					// Initialize a new item and new info.
					newEntry = new EntryImpl(identity, this, this.entry.repositoryManager, this.entry.getRepository());

					// Initialize a new information object.
					if (lType == EntryType.Reference || lType == EntryType.LinkReference) {
						newEntry.create(resURI, vf.createIRI(metadataURI.toString()), bType, lType, rType, rc);
					} else {
						newEntry.create(resURI, null, bType, lType, rType, rc);
					}
					initResource(newEntry);

					// Update index with new item.
					addToIndex(newEntry.getSesameEntryURI(), newEntry.getSesameResourceURI(), newEntry.getSesameExternalMetadataURI(), rc);

					// Update the index counter.
					List<Statement> counters = rc.getStatements(this.resourceURI, RepositoryProperties.counter, null, false, this.resourceURI).stream().toList();
					rc.remove(counters, this.resourceURI);
					rc.add(this.resourceURI, RepositoryProperties.counter, vf.createLiteral(counter), this.resourceURI);

					rc.commit();
					softCache.put(newEntry);
					entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(newEntry, RepositoryEvent.EntryCreated));
					return newEntry;
				} catch (Exception e) {
					rc.rollback();
					if (newEntry != null) {
						newEntry.refreshFromRepository(rc);
					}
					throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
				}
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
				// All other cases are only allowed by ContextManager or PrincipalManager. See overridden method there.
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
	 * @param secondChance typically a list to check if access is given in that list only.
	 * @param ap           the kind of access requested.
	 * @return true if a user is an owner of current context, false otherwise.
	 * @throws AuthorizationException
	 */
	protected boolean checkAccess(Entry secondChance, AccessProperty ap) throws AuthorizationException {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm == null) {
			return true;
		}
		try {
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
		ListImpl list = getList(listURI);
		boolean isOwner = checkAccess(list != null ? list.entry : null, AccessProperty.WriteResource);

		// TODO externalize this into a setting
		boolean allowUserGroupToReadMetadata = true;

		synchronized (this.entry.repository) {
			EntryImpl entry = createNewMinimalItem(null, null, EntryType.Local, buiType, repType, entryId);
			if (list != null) {
				log.info("Adding entry {} to list {}", entry.getEntryURI(), list.getURI());
				list.addChild(entry.getEntryURI());
				log.info("Copying ACL from list {} to entry {}", list.getURI(), entry.getEntryURI());
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
				entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, ((PrincipalManager) this).getUserGroup().getURI());
			} else if (GraphType.Group.equals(buiType)) {
				entry.addAllowedPrincipalsFor(AccessProperty.ReadResource, entry.getResourceURI());
				if (allowUserGroupToReadMetadata) {
					entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, ((PrincipalManager) this).getUserGroup().getURI());
				} else {
					entry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, entry.getResourceURI());
				}
			}

			return entry;
		}
	}

	public void copyACL(org.entrystore.List fromList, Entry toEntry) {
		if (toEntry instanceof EntryImpl entryImpl) {
			Set<URI> adminPrincipals = fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.Administer);
			if (toEntry.getGraphType() != GraphType.List || toEntry.getEntryType() != EntryType.Local) {
				PrincipalManager pm = toEntry.getRepositoryManager().getPrincipalManager();
				try {
					pm.checkAuthenticatedUserAuthorized(fromList.getEntry(), AccessProperty.Administer);
				} catch (AuthorizationException ae) {
					adminPrincipals.add(pm.getAuthenticatedUserURI());
				}
			}
			entryImpl.updateAllowedPrincipalsFor(AccessProperty.Administer, adminPrincipals, false, true);
			entryImpl.updateAllowedPrincipalsFor(AccessProperty.ReadMetadata, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.ReadMetadata), false, true);
			entryImpl.updateAllowedPrincipalsFor(AccessProperty.ReadResource, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.ReadResource), false, true);
			entryImpl.updateAllowedPrincipalsFor(AccessProperty.WriteMetadata, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.WriteMetadata), false, true);
			entryImpl.updateAllowedPrincipalsFor(AccessProperty.WriteResource, fromList.getEntry().getAllowedPrincipalsFor(AccessProperty.WriteResource), false, true);
		} else {
			log.warn("copyACL(fromList, toEntry): Not setting an ACL: toEntry is not an instance of EntryImpl");
		}
	}

	public void copyACL(URI fromList, Entry toEntry) {
		copyACL(getList(fromList), toEntry);
	}

	public Entry get(String entryId) {
		return getByEntryURI(URISplit.createURI(
			entry.getRepositoryManager().getRepositoryURL().toString(),
			id, RepositoryProperties.ENTRY_PATH, entryId));
	}

	public Entry getByEntryURI(URI entryURI) {
		Entry entry = softCache.getByEntryURI(entryURI);
		if (entry != null) {
			return entry;
		}

		try {
			return getByMMdURIDirect(entryURI);
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}

	private Entry getByMMdURIDirect(URI entryURI) throws RepositoryException {
		try (RepositoryConnection rc = this.entry.getRepository().getConnection()) {
			return getByMMdURIDirect(entryURI, rc);
		} catch (RepositoryException e) {
			log.error(e.getMessage());
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
	}

	private Entry getByMMdURIDirect(URI entryURI, RepositoryConnection rc) throws RepositoryException {
		if (entryURI == null) {
			return null;
		}
		EntryImpl newEntry = null;
		try {
			URISplit split = new URISplit(entryURI, this.entry.getRepositoryManager().getRepositoryURL());
			if (!this.id.equals(split.getContextId())) {
				return null;
			}
			try {
				newEntry = new EntryImpl(split.getId(), this, this.entry.repositoryManager, this.entry.getRepository());
			} catch (IllegalArgumentException iae) {
				log.error("Error when creating entry object: {}", iae.getMessage());
			}
			if (newEntry != null && newEntry.load(rc)) {
				if (newEntry.getEntryType() == EntryType.Local) {
					initResource(newEntry);
				}
				softCache.put(newEntry);
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
		if (metadataURI == null) {
			// ConcurrentHashMap.get(null) throws NullPointerException where the HashMap this replaced
			// returned null, and a null argument is a normal state here rather than a caller bug. This
			// method's null arrives from ListRecordsJob, which passes a harvested record's metadata URI
			// without checking it; getByResourceURI's arrives from an unset authenticatedUserURI
			// ThreadLocal. Answering empty keeps the latter an authorization denial rather than a 500
			// raised from inside an authorization check.
			return new HashSet<>();
		}
		return entriesFor(externalMetadataIndex().get(metadataURI));
	}

	public Set<Entry> getByResourceURI(URI resourceURI) {
		if (resourceURI == null) {
			// See getByExternalMdURI. The null reaches here through
			// PrincipalManagerImpl.isUserAdminOrAdminGroup when the authenticatedUserURI ThreadLocal is
			// unset: it leaves the principal null and hands it to getUser, which calls this.
			return new HashSet<>();
		}
		return entriesFor(resourceIndex().get(resourceURI));
	}

	/**
	 * @see Context#isIndexComplete()
	 */
	public boolean isIndexComplete() {
		// Loads first: asking a context that has never been read whether its index is complete must not
		// answer "yes" merely because nothing has been scanned yet.
		loadIndexes();
		return !indexIncomplete;
	}

	public Set<URI> getEntries() {
		//Listing entries should always be allowed?
		//Seeing metadata for each of the entries is determined in the normal way.
		//		checkAccess(null, AccessProperty.ReadResource);

		Set<URI> entries = new HashSet<>();
		for (Object indexValue : resourceIndex().values()) {
			addEntryURIs(indexValue, entries);
		}

		return entries;
	}

	/**
	 * Resolves one index value — a single entry URI or a {@code Set} of them — to the entries it names.
	 */
	private Set<Entry> entriesFor(Object indexValue) {
		Set<URI> entryURIs = new HashSet<>();
		addEntryURIs(indexValue, entryURIs);
		Set<Entry> entries = new HashSet<>();
		for (URI entryURI : entryURIs) {
			entries.add(getByEntryURI(entryURI));
		}
		return entries;
	}

	/**
	 * Adds the entry URIs an index value stands for to {@code target}, unpacking the
	 * {@code URI}-or-{@code Set<URI>} union the indexes store. The single place that cast is made:
	 * {@link #push} and {@link #pop} are the only writers, so no third type can appear.
	 */
	@SuppressWarnings("unchecked")
	private static void addEntryURIs(Object indexValue, Collection<URI> target) {
		if (indexValue instanceof URI entryURI) {
			target.add(entryURI);
		} else if (indexValue != null) {
			target.addAll((Set<URI>) indexValue);
		}
	}

	public Set<URI> getResources() {
		checkAccess(null, AccessProperty.ReadResource);

		// Copied rather than returning keySet() directly: that is a live view of the index, so a caller
		// holding it would observe later changes and could even remove index entries through it.
		return new HashSet<>(resourceIndex().keySet());
	}

	/**
	 * The resource-URI index, loading it first if needed.
	 *
	 * <p>Reads the volatile field once, and otherwise takes what {@link #loadIndexes()} published rather
	 * than re-reading the field: a concurrent {@link #reIndex()} nulling it between a successful load and
	 * a re-read would otherwise turn a perfectly healthy read into a failure, and {@code reIndex()} runs
	 * on every context import. There is no null case left to guard, which is why this no longer throws.
	 */
	private ConcurrentMap<URI, Object> resourceIndex() {
		ConcurrentMap<URI, Object> index = res2entry;
		return index != null ? index : loadIndexes().resource();
	}

	/** The external-metadata-URI index, loading it first if needed. See {@link #resourceIndex()}. */
	private ConcurrentMap<URI, Object> externalMetadataIndex() {
		ConcurrentMap<URI, Object> index = extMdUri2entry;
		return index != null ? index : loadIndexes().externalMetadata();
	}

	public void remove(URI entryURI) throws EntryMissingException {
		if (systemEntries.contains(entryURI)) {
			throw new DisallowedException("Cannot remove system entry with URI: " + entryURI);
		}

		synchronized (this.entry.repository) {
			EntryImpl removeEntry = (EntryImpl) getByEntryURI(entryURI);
			if (removeEntry == null) {
				throw new EntryMissingException(entryURI);
			}
			checkAccess(removeEntry, AccessProperty.Administer);

			try {
				for (URI uri : removeEntry.getReferringListsInSameContext()) {
					Entry listItem = getByResourceURI(uri).iterator().next();
					((ListImpl) listItem.getResource()).removeChild(entryURI, false);
				}
			} catch (Exception e) {
				log.error(e.getMessage(), e);
				throw new org.entrystore.repository.RepositoryException("An error occurred when removing the entry from one or more lists", e);
			}

			RepositoryConnection rc = null;
			try {
				rc = entry.repository.getConnection();
				rc.begin();
				removeFromIndex(removeEntry, rc);
				removeEntry.remove(rc);
				this.entry.updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
				rc.commit();
				softCache.remove(removeEntry);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(removeEntry, RepositoryEvent.EntryDeleted));
			} catch (Exception e) {
				try {
					rc.rollback();
				} catch (NullPointerException | RepositoryException e1) {
					log.error(e1.getMessage());
					throw new org.entrystore.repository.RepositoryException("Error when rolling back transaction", e);
				}
				log.error(e.getMessage(), e);
				throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
			} finally {
				try {
					rc.close();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage(), e);
				}
			}
		}
	}

	public void remove(RepositoryConnection rc) throws Exception {
		synchronized (this.entry.repository) {
			// The child list comes from the transaction, not from the in-memory index. That index is
			// weakly consistent: updateResource2EntryIndex pops and pushes as two separate compute()
			// calls, and EntryImpl.setResourceURI calls it after releasing this monitor, so an entry
			// observed in that gap would keep its entry, metadata and resource graphs while the
			// rc.clear(this.resourceURI) below destroys the graph that named them — permanently orphaned
			// data. A statement the index could not parse would be skipped for the same effect. Reading
			// resHasEntry through rc sees this transaction's own view and cannot miss an entry.
			//
			// Collected as RDF4J IRIs rather than java.net.URIs: this enumeration is fed straight from the
			// store, so unlike the index it once replaced it also sees objects that java.net.URI.create
			// rejects. Round-tripping them would abort the whole deletion on the very statements this
			// change exists to stop the index from hiding.
			Set<IRI> childEntryIRIs = rc
					.getStatements(null, RepositoryProperties.resHasEntry, null, false, this.resourceURI)
					.stream()
					.map(Statement::getObject)
					.filter(IRI.class::isInstance)
					.map(IRI.class::cast)
					.collect(Collectors.toSet());

			// do not move this boolean from here, this is needed to avoid adding
			// entries to solr after they have been removed from solr (race condition)
			deleted = true;

			for (IRI childEntryIRI : childEntryIRIs) {
				EntryImpl removeEntry = resolveChild(childEntryIRI);
				if (removeEntry == null) {
					// The graph is cleared regardless: leaving it behind is what orphans data, and it is
					// named right here. Only the entry object could not be built, so removeFromIndex and
					// the entry's own cleanup are what get skipped.
					log.error("Child {} of context {} could not be loaded as an entry; clearing its graph "
							+ "anyway so the deletion does not leave it orphaned", childEntryIRI, this.id);
					rc.clear(childEntryIRI);
					continue;
				}
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
	 * Loads a child named by the context's index graph, or null when it names nothing loadable — a URI
	 * {@code java.net.URI} rejects, an id belonging to another context, or an entry whose graph is gone.
	 * {@code getByEntryURI} answers null for all three, and this deletion path used to dereference that
	 * immediately, rolling back after {@code deleted = true} had already been set.
	 */
	private EntryImpl resolveChild(IRI childEntryIRI) {
		try {
			return (EntryImpl) getByEntryURI(URI.create(childEntryIRI.toString()));
		} catch (IllegalArgumentException | ClassCastException e) {
			log.error("Child {} of context {} is not a usable entry URI: {}",
					childEntryIRI, this.id, e.getMessage());
			return null;
		}
	}

	/**
	 * @see Context#hasDefaultQuota()
	 */
	public boolean hasDefaultQuota() {
		RepositoryConnection rc = null;
		try {
			rc = entry.repository.getConnection();
			return !rc.hasStatement(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI);
		} catch (RepositoryException re) {
			log.error(re.getMessage(), re);
		} finally {
			try {
				rc.close();
			} catch (NullPointerException | RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return true;
	}

	/**
	 * @see Context#getQuota()
	 */
	public long getQuota() {
		if (this.quota == Quota.VALUE_UNCACHED) {
			long queriedQuota = entry.getRepositoryManager().getDefaultQuota();
			synchronized (this.entry.repository) {
				RepositoryConnection rc = null;
				try {
					rc = entry.repository.getConnection();
					List<Statement> quotaStatement = rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI).stream().toList();
					for (Statement statement : quotaStatement) {
						if (statement.getObject() instanceof Literal) {
							queriedQuota = ((Literal) statement).longValue();
							break;
						}
					}
				} catch (RepositoryException re) {
					log.error(re.getMessage(), re);
				} finally {
					try {
						rc.close();
					} catch (NullPointerException | RepositoryException e) {
						log.error(e.getMessage());
					}
				}
			}
			this.quota = queriedQuota;
		}

		return this.quota;
	}

	/**
	 * @see Context#setQuota(long)
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
				rc.begin();
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI), this.resourceURI);
				rc.add(this.resourceURI, RepositoryProperties.Quota, rc.getValueFactory().createLiteral(quotaInBytes), this.resourceURI);
				rc.commit();
				this.quota = quotaInBytes;
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.EntryUpdated));
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
				try {
					rc.rollback();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage());
				}
			} finally {
				try {
					rc.close();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
	}

	/**
	 * @see Context#removeQuota()
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
				// TODO add transaction
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.Quota, null, false, this.resourceURI), this.resourceURI);
				this.quota = Quota.VALUE_UNCACHED;
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
			} finally {
				try {
					rc.close();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage());
				}
			}
		}
	}

	/**
	 * @see Context#getQuotaFillLevel()
	 */
	public long getQuotaFillLevel() {
		long queriedQuotaFillLevel = Quota.VALUE_UNKNOWN;
		if (this.quotaFillLevel == Quota.VALUE_UNCACHED) {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = null;
				try {
					rc = entry.repository.getConnection();
					List<Statement> quotaStatement = rc.getStatements(this.resourceURI, RepositoryProperties.QuotaFillLevel, null, false, this.resourceURI).stream().toList();
					for (Statement statement : quotaStatement) {
						if (statement.getObject() instanceof Literal) {
							queriedQuotaFillLevel = ((Literal) statement.getObject()).longValue();
							break;
						}
					}
				} catch (RepositoryException re) {
					log.error(re.getMessage(), re);
				} finally {
					try {
						rc.close();
					} catch (NullPointerException | RepositoryException e) {
						log.error(e.getMessage());
					}
				}
			}
			if (queriedQuotaFillLevel == Quota.VALUE_UNKNOWN) {
				if (isIndexComplete()) {
					synchronized (quotaMutex) {
						setQuotaFillLevel(recalculateQuotaFillLevel());
					}
				} else {
					// recalculateQuotaFillLevel sums getEntries(), and setQuotaFillLevel persists the
					// result. Summing a short listing would permanently lower the stored fill level and
					// stop the quota being enforced, so leave it uncached: the level is recomputed on a
					// later read, and by then the data may be repaired.
					log.error("Not recalculating the quota fill level of context {}: its index is "
							+ "incomplete, so the total would be too low and would then be persisted", this.id);
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
		log.info("Calculation of quota fill level took {} ms", new Date().getTime() - before.getTime());
		return fillLevel;
	}

	/**
	 * @see Context#increaseQuotaFillLevel(long)
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
	 * @see Context#decreaseQuotaFillLevel(long)
	 */
	public void decreaseQuotaFillLevel(long bytes) {
		synchronized (quotaMutex) {
			setQuotaFillLevel(getQuotaFillLevel() - bytes);
		}
	}

	/**
	 * FIXME ENTRYSTORE-418
	 * <p>
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
				rc.begin();
				rc.remove(rc.getStatements(this.resourceURI, RepositoryProperties.QuotaFillLevel, null, false, this.resourceURI), this.resourceURI);
				rc.add(this.resourceURI, RepositoryProperties.QuotaFillLevel, rc.getValueFactory().createLiteral(bytes), this.resourceURI);
				rc.commit();
				this.quotaFillLevel = bytes;
			} catch (RepositoryException re) {
				log.error(re.getMessage(), re);
				try {
					rc.rollback();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage());
				}
			} finally {
				try {
					rc.close();
				} catch (NullPointerException | RepositoryException e) {
					log.error(e.getMessage());
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
			Model graph = entry.getLocalMetadata().getGraph();
			IRI root = iri(entry.getResourceURI().toString());
			graph.add(root, TestSuite.dc_title, literal(title, "en"));
			if (desc != null) {
				graph.add(root, TestSuite.dc_description, literal(desc, "en"));
			}
			entry.getLocalMetadata().setGraph(graph);
		} catch (Exception e) {
			log.error(e.getMessage());
		}
	}

}
