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
import org.entrystore.repository.util.URIType;
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
import java.util.LinkedHashMap;
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

	/**
	 * Next entry id to hand out. Volatile because the two paths that touch it hold different monitors:
	 * {@link #scanAndPublish(boolean)} assigns the scanned value under {@code indexLock}, while
	 * {@code createNewMinimalItem} reads and increments it under {@code entry.repository}. Without it
	 * there is no happens-before edge between them, and a non-volatile {@code long} has no atomicity
	 * guarantee either. The increment itself stays serialised by {@code entry.repository}.
	 */
	private volatile long counter = -1;
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
	 * <li><b>volatile</b>, and {@link #scanAndPublish()} assigns the field only once the map is fully
	 * populated. Assigning an empty map first and filling it afterwards let a reader observe a
	 * non-null field, skip loading — and so never contend for its lock — and then iterate
	 * a map still being written.</li>
	 * <li><b>Concurrent maps, with concurrent value sets.</b> Readers hold no monitor, so weakly
	 * consistent iteration at both levels is what makes a listing safe against a concurrent entry
	 * creation or removal instead of throwing {@code ConcurrentModificationException}.</li>
	 * <li><b>Read once into a local</b> before use. {@link #reIndex()} nulls these fields, so reading
	 * the field again after the null check can dereference null, and a {@code pop} followed by a
	 * {@code push} that each re-read it can land on two different maps.</li>
	 * <li><b>No write is dropped, except on one bounded path.</b> A writer that finds the fields
	 * unpublished records its operation in {@link #pendingIndexOps} instead of skipping it; see
	 * {@link #applyIndexOp} for the mechanism and {@link #scanAndPublish()} for the overflow
	 * exception.</li>
	 * </ul>
	 *
	 * <p><b>Lock protocol.</b> {@link #indexLock} is a per-context monitor guarding the publication of
	 * these two fields, {@link #pendingIndexOps}, {@link #pendingIndexOpsOverflows},
	 * {@link #indexIncomplete} and {@link #counter}. It is the only monitor the index paths take, with
	 * the single exception noted below:
	 *
	 * <ul>
	 * <li>writers hold {@code entry.repository} already and acquire {@code indexLock} inside it, so the
	 * order is {@code repository} → {@code indexLock} wherever both are held;</li>
	 * <li>{@link #loadIndexes()} normally scans holding <em>nothing</em>, and acquires only
	 * {@code indexLock} to publish. It deliberately does not take {@code entry.repository}: that is a
	 * single store-wide instance, so holding it across a full context scan stalls every write in every
	 * context — the reason {@code 45cdc777} moved off it in 2020;</li>
	 * <li><b>the exception:</b> a scan that keeps losing a race with an overflowing pending buffer falls
	 * back to scanning inside {@code entry.repository}. That is the one path on which a reader waits for
	 * writers, and the one on which a scan holds the store-wide monitor;</li>
	 * <li>{@link #reIndex()} clears both fields under {@code indexLock}.</li>
	 * </ul>
	 *
	 * The lock order is the same on every path that holds both, so there is no cycle.
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
	 * maps by {@link #scanAndPublish()} before it publishes them.
	 *
	 * <p>This is what makes publish-at-end safe without holding the writers' monitor across the scan. A
	 * writer that simply skipped its push when the indexes were unpublished could lose the mapping for
	 * the life of this object: a scan that started before the write committed cannot see the
	 * {@code resHasEntry} triple either, so the mapping would be missing from both. Neither a write
	 * counter nor a mutex around the index touch closes that — the reader can read the counter after the
	 * writer bumped it and still publish before the commit lands. Recording the operation does close it,
	 * because the publish replays whatever the scan could not see.
	 *
	 * <p>({@link #pendingIndexOpsOverflows} is a counter, but not that counter: it counts times this
	 * buffer was <em>discarded</em>, which is the one case where nothing was recorded. It guards the
	 * fallback, it is not the mechanism.)
	 *
	 * <p>Ops reach here only <em>after</em> their transaction commits; {@code addToIndex} and
	 * {@code removeFromIndex} collect into a per-transaction list that {@link #applyIndexOps} drains once
	 * the commit succeeds. Recording from inside the open transaction instead would survive a rollback,
	 * and a replayed op whose store change was undone is durable index divergence — the mirror of the
	 * problem this buffer exists to solve.
	 *
	 * <p>Only populated between construction (or a {@link #reIndex()}) and the first read, so it is
	 * empty in steady state. {@link #indexOpBufferLimit} bounds a pathological case — a bulk import into
	 * a context nobody reads — after which the buffer is discarded rather than grown; see
	 * {@link #scanAndPublish()} for how a scan that raced such a discard is detected and retried.
	 *
	 * <p>Package-private so {@code ContextImplTest} can assert that a test named for the unpublished
	 * branch really is exercising it, rather than passing identically on the published branch.
	 */
	final List<IndexOp> pendingIndexOps = new ArrayList<>();

	/**
	 * How many times {@link #pendingIndexOps} has been discarded for exceeding
	 * {@link #indexOpBufferLimit}. Guarded by {@link #indexLock}.
	 *
	 * <p>A count rather than a flag because the question a scan has to answer is "was anything dropped
	 * <em>while I was scanning</em>", not "has anything ever been dropped": an overflow that happened
	 * before a scan started is harmless, since every op arrives after its transaction committed and so
	 * is already in the store the scan reads. Each scan samples this at its start and compares at
	 * publish time. A shared flag cleared at the start of a scan answers the same question for one
	 * scanner but not for two — a second cold reader clearing it would hide an overflow the first one
	 * still had to act on.
	 */
	private long pendingIndexOpsOverflows;

	/**
	 * Cap on {@link #pendingIndexOps}; see its javadoc.
	 *
	 * <p>Package-private and non-final so {@code ContextImplTest} can lower it far enough to reach the
	 * overflow path deterministically. It is the one branch of this design that deliberately drops
	 * writes, so leaving it untestable is what let the flag-reset race ship in the first place.
	 *
	 * <p>An instance field, not a static one: a static would be JVM-global, so a test lowering it would
	 * also lower it for {@code _contexts} and {@code _principals} — including the very
	 * {@code createResource} calls such a test makes to build its fixture.
	 */
	int indexOpBufferLimit = 10_000;

	/**
	 * Run inside {@link #scanAndPublish()} between reading the statements and publishing them, or null.
	 *
	 * <p>The only seam in this class, and it exists for one reason: the window in which a write is
	 * dropped and lost is exactly this one, both {@code scanAndPublish} calls are private and
	 * synchronous, and a sequential test cannot produce the interleaving — every write it makes commits
	 * before the read. A round-4 test claimed to guard that race and did not, which is what a seam
	 * prevents from happening again. Instance-scoped so it cannot affect another context's load.
	 */
	volatile Runnable indexScanHookForTests;

	/**
	 * True when the published index is missing at least one statement that could not be indexed.
	 *
	 * <p>A field rather than a local count inside the load, because the consequences outlive the load.
	 * Listings from this context are short, and two consumers act on a short listing destructively
	 * rather than merely incompletely: the Solr reindex purge deletes every unlisted entry from the
	 * search index, and {@code recalculateQuotaFillLevel}'s total is persisted. Both check
	 * {@link #isIndexComplete()} and refuse instead.
	 *
	 * <p>Refusing only works where the destructive step has not happened yet. {@code importContext} and
	 * {@code PublicRepository.rebuildRepository} both used to check this flag and no longer do: they
	 * enumerate through {@link #getChildEntryURIsFromStore()}, so their listing cannot be short in the
	 * first place. In the public-repository case refusing was actively worse than not checking, because
	 * {@code rc.clear()} runs before the loop that would have been skipped.
	 *
	 * <p>Assigned unconditionally on every publish. Note that a published index is never rescanned, so
	 * repairing the underlying data is not enough on its own: only {@link #reIndex()} or a restart
	 * re-reads it and clears this flag.
	 */
	private volatile boolean indexIncomplete;

	/** Which index an {@link IndexOp} applies to. Package-private for the same reason as {@link IndexOp}. */
	enum IndexKind {RESOURCE, EXTERNAL_METADATA}

	/**
	 * A deferred {@code push} or {@code pop}, collected inside the writing transaction and applied once
	 * that transaction commits. Both {@code from} and {@code to} are non-null — {@link #applyIndexOp}
	 * returns early otherwise, so an op with either side null can never be constructed.
	 *
	 * <p>Package-private so {@code EntryNamesContext} can pass the per-transaction list through its
	 * {@code removeFromIndex} override.
	 */
	record IndexOp(IndexKind kind, boolean push, URI from, URI to) {}
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

					// E4 (ENTRYSTORE-1086): the es:resource/es:externalMetadata statements of this
					// context's entries all live in the entries' own named graphs
					// ({contextResourceURI}/entry/{id}), so both scans below are restricted to those
					// graphs instead of walking every matching statement of the whole repository.
					// The empty-array guard matters: RDF4J treats an empty contexts vararg as
					// "all graphs", which would silently undo the scoping.
					String entryNGPrefix = this.resourceURI.stringValue() + "/entry/";
					List<Resource> entryNGs = new ArrayList<>();
					RepositoryResult<Resource> availableNGs = rc.getContextIDs();
					while (availableNGs.hasNext()) {
						Resource ng = availableNGs.next();
						if (ng instanceof IRI && ng.stringValue().startsWith(entryNGPrefix)) {
							entryNGs.add(ng);
						}
					}
					availableNGs.close();
					Resource[] entryNGArray = entryNGs.toArray(new Resource[0]);

					// create a new index by finding all sesame contexts that belong to this context
					int maxIndex = 0;
					if (entryNGArray.length > 0) {
						RepositoryResult<Statement> resources = rc.getStatements(null, RepositoryProperties.resource, null, false, entryNGArray);
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
										// A silently skipped token would let reIndex write a too-low
										// counter and later createResource calls mint colliding ids.
										log.warn("Ignoring non-numeric entry id token in {} during reIndex of context {}",
												rI, this.id);
									}
									// this does not work: addToIndex((org.openrdf.model.URI) statement.getSubject(),(org.openrdf.model.URI) statement.getObject(),(org.openrdf.model.URI) statement.getContext(), rc);
									stmntsToAdd.add(vf.createStatement((Resource) statement.getObject(), RepositoryProperties.resHasEntry, statement.getContext(), this.resourceURI));
								}
							}
						}
						resources.close();

						RepositoryResult<Statement> externalMD = rc.getStatements(null, RepositoryProperties.externalMetadata, null, false, entryNGArray);
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

	/**
	 * Forces the indexes to be loaded, discarding the result. A convenience for callers that only want
	 * the side effect; the scanning, publication and lock protocol all live in {@link #loadIndexes()}.
	 */
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
	 * <p>One case still takes {@code entry.repository}: if a scan keeps racing an overflowing buffer
	 * there is no record of what was dropped, so this falls back to excluding writers. That needs
	 * {@link #indexOpBufferLimit} un-read writes to a single context to happen at all.
	 */
	private Indexes loadIndexes() {
		Indexes published = publishedIndexes();
		if (published != null) {
			return published;
		}
		// Null means the buffer overflowed while this scan was running, so the scan may be short and
		// there is no longer a record of what it missed.
		Indexes scanned = scanAndPublish();
		if (scanned != null) {
			return scanned;
		}
		synchronized (this.entry.repository) {
			// Re-checked under the monitor: M cold readers of the same context would otherwise each run
			// two full scans, serialised here, with M-1 of them discarded.
			Indexes concurrentlyPublished = publishedIndexes();
			if (concurrentlyPublished != null) {
				return concurrentlyPublished;
			}
			// Retried rather than trusted once. Holding this monitor does not stop every index write:
			// EntryImpl.setResourceURI and setExternalMetadataURI release it before calling in, so one
			// already past its commit can still trip the limit mid-scan. What the monitor does stop is
			// any further write from committing, so each retry would need a whole buffer's worth of ops
			// from threads that were already in that window — which is why this terminates.
			Indexes exclusivelyScanned;
			while ((exclusivelyScanned = scanAndPublish()) == null) {
				log.warn("Index scan of context {} raced an overflowing pending-write buffer; rescanning",
						this.id);
			}
			return exclusivelyScanned;
		}
	}

	/**
	 * Scans and publishes, or returns null when the pending buffer overflowed <em>while this scan was in
	 * flight</em> — the scan may then be short with no record of what it missed, and the caller retries.
	 *
	 * <p>Detected by comparing {@link #pendingIndexOpsOverflows} across the scan, rather than by a flag
	 * a publish resets. An overflow that happened before this scan began is harmless: every op reaches
	 * {@link #applyIndexOp} only after its transaction committed, so a scan starting now reads from the
	 * store whatever the dropped ops did. Only an overflow during the scan can leave it short. A flag
	 * reset after publishing answered a different question — "has an overflow ever happened" — and got
	 * both directions wrong: it let a scan that had compensated for nothing clear it, and it forced a
	 * rescan when the dropped writes were already in the store.
	 */
	private Indexes scanAndPublish() {
		long overflowsAtScanStart;
		synchronized (indexLock) {
			Indexes alreadyPublished = publishedIndexes();
			if (alreadyPublished != null) {
				return alreadyPublished;
			}
			overflowsAtScanStart = pendingIndexOpsOverflows;
		}
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
					// unresolvable principal is fail-open in PrincipalManagerImpl.hasAccess, and both the
					// Solr purge and the persisted quota fill level act destructively on a listing. Those
					// two check isIndexComplete() and refuse. The two consumers that cannot refuse —
					// importContext and the public-repository rebuild, which have already destroyed
					// something by the time they could ask — enumerate through
					// getChildEntryURIsFromStore() instead, so their listing is never short.
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

		// Test seam. Lets a test commit a write, and overflow the buffer, at the one point where doing so
		// leaves this scan short — between reading the statements and publishing them. Instance-scoped
		// rather than static, so it cannot leak into another context's load.
		Runnable hook = indexScanHookForTests;
		if (hook != null) {
			hook.run();
		}

		synchronized (indexLock) {
			// Both fields, not just res2entry: guarding on one alone made a half-cleared pair
			// unrecoverable, because this early return would then skip rebuilding the other.
			Indexes concurrentlyPublished = publishedIndexes();
			if (concurrentlyPublished != null) {
				// Another loader won the race; discard this scan rather than replacing a live index.
				return concurrentlyPublished;
			}
			if (pendingIndexOpsOverflows != overflowsAtScanStart) {
				// A writer tripped the limit and dropped the buffer while this scan was running, so the
				// scan may be short and there is no record of what it missed. Publishing here is what
				// silently lost those writes.
				return null;
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
	 * Applies every collected op once the transaction that produced them has committed. Ops recorded
	 * inside an open transaction must not reach the index before the commit lands: a rolled-back
	 * {@code remove} would otherwise pop a mapping whose triple was restored in the store, leaving a live
	 * entry absent from every listing, and a rolled-back create would leave a mapping to an entry that
	 * never existed.
	 *
	 * <p>Safe against the race this design exists to close. By the time these are applied the store
	 * change is committed, so an op either finds the indexes published and applies directly, or is
	 * buffered under {@code indexLock} before any scan can replay-and-clear.
	 */
	private void applyIndexOps(List<IndexOp> txOps) {
		for (IndexOp op : txOps) {
			applyIndexOp(op.kind(), op.push(), op.from(), op.to());
		}
	}

	/**
	 * Applies one index write, or records it for {@link #loadIndexes()} to replay when the indexes are
	 * not published yet.
	 *
	 * <p>Where both monitors are held the order is {@code repository} → {@code indexLock}. Not every
	 * caller holds {@code entry.repository}: {@code EntryImpl.setResourceURI} and
	 * {@code setExternalMetadataURI} release it before calling in through
	 * {@link #updateResource2EntryIndex}.
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
			if (pendingIndexOps.size() >= indexOpBufferLimit) {
				// Give up on replay rather than growing without limit. A scan in flight is discarded and
				// retried, eventually with writers excluded, which is correct — just coarser.
				log.warn("More than {} index writes to context {} before it was ever read; an index load "
								+ "running concurrently will be discarded and retried",
						indexOpBufferLimit, this.id);
				pendingIndexOpsOverflows++;
				// Keep this op rather than discarding it with the rest: it costs nothing and makes the
				// dropped set exactly the ops the compensating rescan reconstructs from the store.
				pendingIndexOps.clear();
				pendingIndexOps.add(new IndexOp(kind, isPush, from, to));
				return;
			}
			pendingIndexOps.add(new IndexOp(kind, isPush, from, to));
		}
	}

	/**
	 * Adds the index triples inside {@code rc}'s transaction and collects the matching index operations
	 * into {@code txOps}. The caller applies them with {@link #applyIndexOps} once the commit succeeds,
	 * and simply drops the list on rollback — the index must not diverge from what the store kept.
	 */
	void addToIndex(IRI entryURI, IRI resURI, IRI extMdURI, RepositoryConnection rc,
			List<IndexOp> txOps) throws RepositoryException {
		rc.add(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);
		URI euri = URI.create(entryURI.toString());

		if (extMdURI != null) {
			rc.add(extMdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			txOps.add(new IndexOp(IndexKind.EXTERNAL_METADATA, true, URI.create(extMdURI.toString()), euri));
		}
		txOps.add(new IndexOp(IndexKind.RESOURCE, true, URI.create(resURI.toString()), euri));
	}

	/** Collects rather than applies; see {@link #addToIndex}. */
	protected void removeFromIndex(EntryImpl entry, RepositoryConnection rc, List<IndexOp> txOps)
			throws RepositoryException {
		IRI entryURI = entry.getSesameEntryURI();
		IRI resURI = entry.getSesameResourceURI();
		IRI mdURI = entry.getSesameExternalMetadataURI();

		rc.remove(resURI, RepositoryProperties.resHasEntry, entryURI, this.resourceURI);

		if (mdURI != null) {
			rc.remove(mdURI, RepositoryProperties.mdHasEntry, entryURI, this.resourceURI);
			txOps.add(new IndexOp(IndexKind.EXTERNAL_METADATA, false,
					entry.getExternalMetadataURI(), entry.getEntryURI()));
		}
		txOps.add(new IndexOp(IndexKind.RESOURCE, false, entry.getResourceURI(), entry.getEntryURI()));

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
		RepositoryConnection batchRc = entry.repositoryManager.getActiveBatchConnection();
		if (batchRc != null) {
			try {
				return doCreateNewMinimalItem(batchRc, false, resourceURI, metadataURI, lType, bType, rType, entryId);
			} catch (RepositoryException e) {
				throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
			}
		}
		try {
			try (RepositoryConnection rc = entry.repository.getConnection()) {
				return doCreateNewMinimalItem(rc, true, resourceURI, metadataURI, lType, bType, rType, entryId);
			}
		} catch (RepositoryException e) {
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
	}

	/**
	 * Creates the entry on {@code rc}. With {@code manageTx} the caller's connection is this
	 * method's to begin and commit; without it the connection belongs to a batch that commits
	 * later, so this method neither begins, commits nor rolls back, and hands its index ops to
	 * the batch instead of applying them.
	 */
	private EntryImpl doCreateNewMinimalItem(RepositoryConnection rc, boolean manageTx, URI resourceURI, URI metadataURI, EntryType lType, GraphType bType, ResourceType rType, String entryId) {
		ValueFactory vf = entry.repository.getValueFactory();
		if (manageTx) {
			rc.begin();
		}

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
		// Everything after rc.commit() runs on a transaction that has already landed, so a failure
		// there must not be reported as, or answered with, a rollback: rolling back a committed
		// transaction fails in its own right and that secondary failure would replace the original
		// exception before anything logs it, while the caller is told the create did not happen.
		boolean committed = false;
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

			// Update index with new item. Collected, not applied: this transaction commits below —
			// or, in a batch, when the batch ends — and an index op that outlived a rollback would
			// name an entry that never existed.
			List<IndexOp> txOps = new ArrayList<>();
			addToIndex(newEntry.getSesameEntryURI(), newEntry.getSesameResourceURI(), newEntry.getSesameExternalMetadataURI(), rc, txOps);

			// Update the index counter.
			List<Statement> counters = rc.getStatements(this.resourceURI, RepositoryProperties.counter, null, false, this.resourceURI).stream().toList();
			rc.remove(counters, this.resourceURI);
			rc.add(this.resourceURI, RepositoryProperties.counter, vf.createLiteral(counter), this.resourceURI);

			if (manageTx) {
				rc.commit();
				committed = true;
				applyIndexOps(txOps);
			} else {
				entry.repositoryManager.runAfterBatchCommit(() -> applyIndexOps(txOps));
			}
			softCache.put(newEntry);
			entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(newEntry, RepositoryEvent.EntryCreated));
			return newEntry;
		} catch (Exception e) {
			if (committed) {
				log.error("Entry {} in context {} was committed, but the work after the commit failed; "
								+ "the entry exists in the store",
						newEntry != null ? newEntry.getEntryURI() : identity, this.id, e);
				throw new org.entrystore.repository.RepositoryException(
						"Entry was created but could not be published to the in-memory state", e);
			}
			if (manageTx) {
				rc.rollback();
				if (newEntry != null) {
					newEntry.refreshFromRepository(rc);
				}
			}
			throw new org.entrystore.repository.RepositoryException("Error in connection to repository", e);
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
			// returned null. These are public lookup methods, so a null argument is answered rather than
			// thrown on: "no entry has that URI" is the truthful answer and matches the pre-existing
			// contract callers were written against. It also matters concretely for getByResourceURI,
			// whose null arrives from an unset authenticatedUserURI ThreadLocal — answering empty keeps
			// that an authorization denial rather than a 500 raised from inside an authorization check.
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
			// See createNewMinimalItem: a failure after the commit must not be answered with a rollback of
			// an already committed transaction, whose own failure would replace the original exception.
			boolean committed = false;
			try {
				rc = entry.repository.getConnection();
				rc.begin();
				// Collected, not applied: any of the three calls below can throw and roll back, which
				// restores the resHasEntry triple. Popping it anyway would hide a live entry from every
				// listing for the life of this object, with isIndexComplete() still answering true.
				List<IndexOp> txOps = new ArrayList<>();
				removeFromIndex(removeEntry, rc, txOps);
				removeEntry.remove(rc);
				this.entry.updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
				rc.commit();
				committed = true;
				applyIndexOps(txOps);
				softCache.remove(removeEntry);
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(removeEntry, RepositoryEvent.EntryDeleted));
			} catch (Exception e) {
				if (committed) {
					log.error("Entry {} in context {} was removed from the store, but the work after the "
							+ "commit failed; the removal itself stands", entryURI, this.id, e);
					throw new org.entrystore.repository.RepositoryException(
							"Entry was removed but the in-memory state could not be updated", e);
				}
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

	/**
	 * Removes all non-system entries as part of the supplied transaction, so that a caller performing a
	 * larger operation (e.g. context import) can roll back the removals together with the rest of the
	 * transaction. Entries that are named in the index graph but cannot be loaded are skipped, not removed.
	 *
	 * <p>The children come from the store through the supplied connection, not from {@link #getEntries()}:
	 * a statement the in-memory index could not parse makes that listing short, and an entry missing from it
	 * would survive a replace-import and coexist with the imported data (ENTRYSTORE-1095).
	 *
	 * <p>The three accumulators are filled as the removal runs, so they reflect the progress made even if
	 * this method throws: {@code removedEntries} every entry whose removal has started,
	 * {@code deferredFileDeletions} the file resources whose disk files must be deleted after a successful
	 * commit, and {@code prunedSurvivingLists} the entries of surviving lists whose membership was pruned.
	 * After a commit the caller must invoke {@link #evictFromCaches(List)} with the removed entries, fire
	 * {@link RepositoryEvent#EntryDeleted} for each of them and delete the deferred files; after a rollback
	 * it must invoke {@link #recoverFromFailedRemoval(List)} instead and refresh this context's entry and the
	 * pruned list entries from the repository, whose modification dates (and the lists' contributors) are
	 * updated in memory as part of the removal.
	 */
	protected void removeNonSystemEntries(RepositoryConnection rc, List<EntryImpl> removedEntries,
			List<DataImpl> deferredFileDeletions, List<EntryImpl> prunedSurvivingLists) throws Exception {
		synchronized (this.entry.repository) {
			Map<URI, SurvivingListPrune> survivingListPrunes = new LinkedHashMap<>();
			// Collected and then discarded: both required follow-ups unpublish the two indexes, so the
			// next read rebuilds them from the store. Applying an op instead would buffer a pop into
			// pendingIndexOps while they are unpublished, and a rolled-back removal would have that pop
			// replayed onto the freshly loaded index, hiding a live entry from every listing.
			List<IndexOp> discardedOps = new ArrayList<>();
			for (URI entryURI : childEntryURIsFromStore(rc)) {
				EntryImpl removeEntry = (EntryImpl) getByEntryURI(entryURI);
				if (removeEntry == null) {
					log.warn("Entry {} is named by the context index graph but could not be loaded, "
							+ "skipping removal and removing the stale index reference", entryURI);
					removeStaleIndexReference(entryURI, rc);
					continue;
				}
				if (removeEntry.getId().startsWith("_") || systemEntries.contains(entryURI)) {
					continue;
				}
				checkAccess(removeEntry, AccessProperty.Administer);
				log.info("Removing {}", entryURI);
				collectSurvivingListPrunes(removeEntry, survivingListPrunes);
				removedEntries.add(removeEntry);
				removeFromIndex(removeEntry, rc, discardedOps);
				removeEntry.remove(rc, deferredFileDeletions);
			}
			// prune each surviving list once for all of its removed members: one graph read and rewrite
			// per list instead of one per member
			for (SurvivingListPrune prune : survivingListPrunes.values()) {
				EntryImpl listEntry = (EntryImpl) prune.list().getEntry();
				if (!prunedSurvivingLists.contains(listEntry)) {
					prunedSurvivingLists.add(listEntry);
				}
				prune.list().removeChildrenInTransaction(prune.memberURIsToRemove(), rc);
			}
			this.entry.updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
		}
	}

	private void removeStaleIndexReference(URI entryURI, RepositoryConnection rc) throws RepositoryException {
		IRI entryIRI = rc.getValueFactory().createIRI(entryURI.toString());
		rc.remove((Resource) null, RepositoryProperties.resHasEntry, entryIRI, this.resourceURI);
		rc.remove((Resource) null, RepositoryProperties.mdHasEntry, entryIRI, this.resourceURI);
	}

	/** The members to prune from one surviving list after the bulk-removal loop has finished. */
	private record SurvivingListPrune(ListImpl list, List<URI> memberURIsToRemove) {}

	/**
	 * Collects the prunes needed for referring lists that survive the bulk removal (system entries and
	 * entries with an id starting with "_"), so no dangling member references remain after the transaction
	 * commits. Keyed by list entry URI, so each surviving list is pruned exactly once. Lists that are
	 * themselves being removed are left alone — their graphs are cleared by their own removal.
	 */
	private void collectSurvivingListPrunes(EntryImpl removeEntry, Map<URI, SurvivingListPrune> survivingListPrunes) {
		for (URI listURI : removeEntry.getReferringListsInSameContext()) {
			boolean handled = false;
			for (Entry referringEntry : getByResourceURI(listURI)) {
				EntryImpl listEntry = (EntryImpl) referringEntry;
				boolean listSurvives = listEntry.getId().startsWith("_") || systemEntries.contains(listEntry.getEntryURI());
				if (!listSurvives) {
					// the list is removed itself, its graph is cleared by its own removal
					handled = true;
					continue;
				}
				if (listEntry.getResource() instanceof ListImpl list) {
					survivingListPrunes
							.computeIfAbsent(listEntry.getEntryURI(), uri -> new SurvivingListPrune(list, new ArrayList<>()))
							.memberURIsToRemove().add(removeEntry.getEntryURI());
					handled = true;
				}
			}
			if (!handled) {
				log.warn("Referring list {} of removed entry {} could not be resolved to a list, "
						+ "a dangling member reference may remain", listURI, removeEntry.getEntryURI());
			}
		}
	}

	/**
	 * Restores in-memory state after a rolled-back removal: clears the entries' deleted flag (the repository
	 * still contains them) and evicts them from the caches.
	 */
	protected void recoverFromFailedRemoval(List<EntryImpl> entries) {
		for (EntryImpl entryToRecover : entries) {
			entryToRecover.resetDeleted();
		}
		evictFromCaches(entries);
	}

	/**
	 * Evicts the given entries from the soft cache and resets the in-memory URI index, so both are reloaded
	 * from the repository on next access. Must be called once a transaction that removed entries has
	 * finished: after a commit the cached objects are stale, after a rollback the index and the entry objects
	 * have already been mutated.
	 */
	protected void evictFromCaches(List<EntryImpl> entries) {
		synchronized (this.entry.repository) {
			for (EntryImpl entryToEvict : entries) {
				softCache.remove(entryToEvict);
			}
			res2entry = null;
			extMdUri2entry = null;
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
			Set<IRI> childEntryIRIs = new HashSet<>();
			for (Statement statement : rc
					.getStatements(null, RepositoryProperties.resHasEntry, null, false, this.resourceURI)
					.stream()
					.toList()) {
				if (statement.getObject() instanceof IRI childEntryIRI) {
					childEntryIRIs.add(childEntryIRI);
				} else {
					// Reported rather than dropped silently: such a child is neither cleared here nor
					// visible to any listing, so without this line it leaves no trace anywhere.
					log.error("Index statement {} in context {} names a child that is not an IRI; it cannot "
							+ "be cleared and needs manual cleanup", statement, this.id);
				}
			}

			// do not move this boolean from here, this is needed to avoid adding
			// entries to solr after they have been removed from solr (race condition)
			deleted = true;

			// Collected and then discarded: this deletes the whole context graph, so there is no index
			// left to keep in step, and `deleted` above has already taken this context out of service.
			List<IndexOp> discardedOps = new ArrayList<>();
			for (IRI childEntryIRI : childEntryIRIs) {
				// Ownership is checked before anything is resolved or cleared, and for every child rather
				// than only the unresolvable ones. resolveChild cannot be trusted to reject a foreign
				// entry: SoftCache is repository-wide, so getByEntryURI answers from it for any context's
				// entry and only the cache-miss path reaches getByMMdURIDirect's same-context check.
				URISplit childSplit = childSplitIfOwned(childEntryIRI);
				if (childSplit == null) {
					continue;
				}
				EntryImpl removeEntry = resolveChild(childEntryIRI);
				if (removeEntry == null) {
					clearUnresolvableChild(childEntryIRI, childSplit, rc);
					continue;
				}
				removeFromIndex(removeEntry, rc, discardedOps);
				rc.clear(removeEntry.getSesameEntryURI());
				if (!systemEntries.contains(removeEntry.getEntryURI())) {
					removeEntry.remove(rc);
				}
			}
			rc.clear(this.resourceURI);
		}
	}

	/**
	 * The entry URIs this context's index graph names, read straight from the store rather than from the
	 * in-memory index.
	 *
	 * <p>For callers that must enumerate every child even when the index could not parse some of them,
	 * because they have already destroyed something by the time they could check {@link
	 * #isIndexComplete()} and refuse. {@code ContextManagerImpl.importContext} deletes what it finds here
	 * before importing, so a listing short by one statement would leave that entry alive alongside the
	 * imported data; {@code PublicRepository.rebuildRepository} clears the whole public repository before
	 * re-adding what it enumerates, so a short listing drops those entries until the next restart.
	 * Reading through the store makes both independent of the index rather than gated on it.
	 *
	 * <p>Objects that are not IRIs, and children that do not belong to this context, are reported and
	 * skipped. The ownership filter lives here rather than in each caller because both of them act
	 * destructively on what this returns — {@code importContext} removes it, the public-repository
	 * rebuild republishes it — and {@code SoftCache} being repository-wide means a caller cannot detect
	 * a foreign child by getting null back from {@code getByEntryURI}: a cached one comes back live. See
	 * {@link #childSplitIfOwned}.
	 *
	 * <p>Holds no monitor. It is a read-only enumeration on its own connection and the lock protocol on
	 * {@link #res2entry} already has scans holding nothing, so taking the store-wide monitor here would
	 * stall every write in every context for the length of the enumeration without being reentrant with
	 * the caller.
	 */
	public Set<URI> getChildEntryURIsFromStore() {
		try (RepositoryConnection rc = entry.repository.getConnection()) {
			return childEntryURIsFromStore(rc);
		} catch (RepositoryException e) {
			log.error(e.getMessage(), e);
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository", e);
		}
	}

	/**
	 * {@link #getChildEntryURIsFromStore()} on a caller-supplied connection, for
	 * {@link #removeNonSystemEntries} which must see its own transaction's view of the index graph.
	 * Materialised rather than iterated lazily, since that caller mutates the graph this walks.
	 */
	private Set<URI> childEntryURIsFromStore(RepositoryConnection rc) {
		Set<URI> children = new HashSet<>();
		try (RepositoryResult<Statement> statements = rc
				.getStatements(null, RepositoryProperties.resHasEntry, null, false, this.resourceURI)) {
			for (Statement statement : statements) {
				if (!(statement.getObject() instanceof IRI childEntryIRI)) {
					log.error("Index statement {} in context {} names a child that is not an IRI; it cannot "
							+ "be enumerated and needs manual cleanup", statement, this.id);
					continue;
				}
				if (childSplitIfOwned(childEntryIRI) == null) {
					continue;
				}
				children.add(URI.create(childEntryIRI.toString()));
			}
		}
		return children;
	}

	/**
	 * The parsed child URI when this context's index graph names a child that really belongs to this
	 * context, or null — having logged why — when it does not.
	 *
	 * <p>Deleting a context must not touch another context's data, and the index graph can name one
	 * without any attacker involved: {@code ContextManagerImpl.importContext} copies index-graph objects
	 * verbatim unless they carry the old context namespace, so restoring an export into the same
	 * repository is enough to plant one. Neither {@link #resolveChild} nor the {@code systemEntries}
	 * guard catches it — {@code SoftCache} is repository-wide, so {@code getByEntryURI} happily returns
	 * another context's entry (or {@code _principals/entry/_admin}) whenever it is cached, and the
	 * deletion would then run the full removal against it.
	 */
	private URISplit childSplitIfOwned(IRI childEntryIRI) {
		URISplit split;
		try {
			split = new URISplit(URI.create(childEntryIRI.toString()),
					entry.getRepositoryManager().getRepositoryURL());
		} catch (IllegalArgumentException e) {
			// Cannot be attributed to any context, so it cannot be shown to be ours. Clearing an
			// unattributable graph is the one outcome worse than leaving it: it can belong to anyone.
			log.error("Child {} named by context {}'s index graph is not a usable EntryStore URI ({}); "
					+ "leaving its graphs alone, they need manual cleanup", childEntryIRI, this.id, e.getMessage());
			return null;
		}
		// Ids, not URIs. split.getContextURI() is base + contextId, which equals this.resourceURI only
		// while a context's resource URI is base + its own id — false for ContextManagerImpl, whose
		// resourceURI is _contexts/resource/_contexts while its children are _contexts/entry/{id}. This is
		// the comparison getByMMdURIDirect already makes.
		if (!this.id.equals(split.getContextId())) {
			log.error("Child {} named by context {}'s index graph belongs to context {}; leaving it alone. "
							+ "The index graph of this context should not name it and needs manual cleanup",
					childEntryIRI, this.id, split.getContextId());
			return null;
		}
		// Owning the context is not enough: the caller derives graph names from the split, and URISplit
		// has an early-return branch (a query string after the context id) that leaves path and id null
		// while still setting contextId. Such a URI would pass the check above and yield ".../metadata/null"
		// and siblings, so require the split to actually name an entry.
		if (split.getUriType() != URIType.MetaMetadata) {
			log.error("Child {} named by context {}'s index graph does not name an entry of it; leaving its "
					+ "graphs alone, they need manual cleanup", childEntryIRI, this.id);
			return null;
		}
		return split;
	}

	/**
	 * Clears the graphs of a child this context owns but {@link #resolveChild} could not load. Leaving it
	 * behind is what orphans data; ownership has already been established by {@link #childSplitIfOwned}.
	 *
	 * <p>Every graph the entry occupies is cleared, via {@link URISplit#getEntryGraphURIs()} rather than
	 * a hand-picked list. {@code removeEntry.remove(rc)} normally clears them and is exactly the call
	 * this path skips, so clearing a subset destroys the graph that <em>names</em> the rest and leaves
	 * the rest behind as unreachable data — the orphan this method exists to prevent. The resource graph
	 * is the one that gets forgotten: {@code EntryImpl.remove} reaches it through
	 * {@code resource.remove(rc)}, and a {@code Link} has no resource graph, so a {@code List},
	 * {@code Graph} or {@code Pipeline} child is the only kind that shows the difference.
	 */
	private void clearUnresolvableChild(IRI childEntryIRI, URISplit split, RepositoryConnection rc) {
		log.error("Child {} of context {} could not be loaded as an entry; clearing its entry, metadata, "
						+ "cached-external-metadata, relation and resource graphs so the deletion does not "
						+ "leave it orphaned. Inverse relations held by other contexts, and the data file of "
						+ "a Data entry, were NOT removed and need manual cleanup", childEntryIRI, this.id);
		ValueFactory vf = rc.getValueFactory();
		rc.clear(split.getEntryGraphURIs().stream()
				.map(uri -> vf.createIRI(uri.toString()))
				.toArray(IRI[]::new));
	}

	/**
	 * Loads a child named by the context's index graph, or null when it names nothing loadable — a URI
	 * {@code java.net.URI} rejects, or an entry whose graph is gone. This deletion path used to
	 * dereference that null immediately, rolling back after {@code deleted = true} had already been set.
	 *
	 * <p>It does <em>not</em> reject another context's entry, which is why {@link #childSplitIfOwned}
	 * runs first and for every child. Only {@code getByMMdURIDirect} applies the same-context check, and
	 * {@code SoftCache} is repository-wide, so a foreign entry that happens to be cached comes back live
	 * and the caller would run the full removal against it.
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
