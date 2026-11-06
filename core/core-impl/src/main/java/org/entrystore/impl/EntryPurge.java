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
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.Group;
import org.entrystore.User;
import org.entrystore.repository.CorruptEntryException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.util.URISplit;
import org.entrystore.repository.util.URIType;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * Removes entries of one context by exact quad, so that the transaction holding the repository monitor finds what it
 * removes through a triple index rather than by scanning the store. {@link #plan} reads what to remove without the
 * monitor. In the caller's transaction, under the monitor, {@link #refresh} reads again the entries changed since and
 * {@link #apply} removes. After the commit the caller runs {@link #publish} still holding the monitor and
 * {@link #afterPublish} without it; on a failure, {@link #rollBack}.
 *
 * <p>For each entry it removes what {@code ContextImpl.remove} does: the entry's entry, metadata, cached external
 * metadata, relations and resource graphs, the relation copies its entry and metadata graphs put in other entries'
 * relations graphs, the membership copies a removed list put in its remaining members' relations graphs, and its
 * index triples. It records a tombstone when deleted entries are tracked, prunes the entry from the lists of the
 * context that remain, stamps the context modified, and deletes the entry's data file once committed. Unlike
 * {@code ContextImpl.remove}, which prunes those lists in transactions of their own before its commit, it prunes them
 * in the caller's transaction and fires every event after the commit; and it keeps a relation copy that a remaining
 * graph still states.
 *
 * <p>An entry changed after the plan is found by its modified date, which writes to its metadata and resource graphs
 * and most writes to its entry graph stamp, and by its list memberships, which are read through an index. Its ACL
 * statements, whose change stamps nothing, are removed by a pattern bound to their subjects. Known limit: a relation
 * copy that an entry not removed writes into a removed entry's relations graph between the plan and the apply is left
 * behind.
 *
 * <p>Refuses a context that keeps entry names and an entry whose resource is a context, user or group, whose removal
 * does more than this.
 */
@Slf4j
final class EntryPurge {

	private final ContextImpl context;
	private final RepositoryManagerImpl repositoryManager;
	private final Repository repository;
	private final ExactRemoval exactRemoval;
	private final boolean blankNodesByPattern;

	/** The relations graphs the planned entries have by convention, whose copies go with them. */
	private final Set<IRI> plannedRelations = new HashSet<>();
	private final Map<IRI, Planned> planned = new LinkedHashMap<>();

	/** The remaining lists of the context that lose members. */
	private final Map<EntryImpl, Set<URI>> prunes = new LinkedHashMap<>();
	/** The remaining lists loaded so far, by entry URI; null for one that is no local list of the context. */
	private final Map<IRI, EntryImpl> listEntries = new HashMap<>();

	/** {@code (list hasListMember member)} in the relations graph of each remaining member of a list it leaves. */
	private final Set<Statement> memberCopies = new LinkedHashSet<>();
	private final Set<IRI> unlinkedMembers = new LinkedHashSet<>();

	private final List<EntryImpl> prunedLists = new ArrayList<>();

	private EntryPurge(ContextImpl context) {
		if (context instanceof EntryNamesContext) {
			throw new IllegalArgumentException("Context " + context.id + " keeps entry names, which a purge does not "
					+ "remove");
		}
		this.context = context;
		this.repositoryManager = context.entry.repositoryManager;
		this.repository = repositoryManager.getRepository();
		this.exactRemoval = new ExactRemoval(repositoryManager);
		this.blankNodesByPattern = repository instanceof SPARQLRepository;
	}

	/**
	 * Reads, without the monitor, what removing the entries takes.
	 *
	 * @param entries entry URIs of the context; one of another context is skipped
	 * @throws IllegalArgumentException if the context keeps entry names
	 * @throws IllegalStateException    if an entry's resource is a context, user or group
	 */
	static EntryPurge plan(ContextImpl context, Collection<IRI> entries) {
		EntryPurge purge = new EntryPurge(context);
		try (RepositoryConnection reader = purge.repository.getConnection()) {
			purge.read(reader, entries, false);
			purge.link(reader);
		}
		return purge;
	}

	/** How many statements the planned entries' own graphs hold. */
	long statementCount() {
		return planned.values().stream().mapToLong(Planned::statementCount).sum();
	}

	/** The remaining lists {@link #apply} removed members from. */
	List<EntryImpl> prunedLists() {
		return List.copyOf(prunedLists);
	}

	/** The members of the removed lists that are not removed themselves, as last read. */
	Set<IRI> unlinkedMembers() {
		return Set.copyOf(unlinkedMembers);
	}

	/** The resources of the lists holding a planned entry, as last read; none for an entry not planned. */
	Set<IRI> holdingLists(IRI entry) {
		Planned p = planned.get(entry);
		return p != null ? Set.copyOf(p.lists()) : Set.of();
	}

	/**
	 * Reads again the entries changed since they were read: those whose modified date or list memberships differ.
	 * Both are looked up through an index; the graphs of a changed entry are read again, in one store pass when many
	 * changed. Within the caller's transaction under the monitor before {@link #apply}, and may be called before as
	 * well, so that only what changes after is read under the monitor.
	 *
	 * @return how many entries were read again
	 */
	int refresh(RepositoryConnection rc) {
		List<IRI> stale = planned.values().stream().filter(p -> isStale(rc, p)).map(Planned::entryURI).toList();
		if (!stale.isEmpty()) {
			read(rc, stale, true);
			link(rc);
		}
		return stale.size();
	}

	/**
	 * Removes, within the caller's transaction under the monitor, what the plan read: each statement by exact quad,
	 * the index triples by a pattern the posc index answers, the ACL statements by a pattern bound to their subject,
	 * and the members of the remaining lists by {@link ListImpl#pruneExactly}. Stamps the context modified.
	 */
	void apply(RepositoryConnection rc) {
		List<Statement> copies = new ArrayList<>();
		Set<IRI> removedGraphs = new HashSet<>();
		for (Planned p : planned.values()) {
			for (List<Statement> statements : p.graphs().values()) {
				ExactRemoval.removeOwn(rc, statements, blankNodesByPattern, "context " + context.id);
			}
			removedGraphs.addAll(p.graphs().keySet());
			copies.addAll(p.copies());
		}
		exactRemoval.removeCopies(rc, copies, removedGraphs::contains);
		rc.remove(memberCopies);

		for (Planned p : planned.values()) {
			rc.remove((Resource) null, RepositoryProperties.resHasEntry, p.entryURI(), context.resourceURI);
			rc.remove((Resource) null, RepositoryProperties.mdHasEntry, p.entryURI(), context.resourceURI);
			for (IRI subject : p.aclSubjects()) {
				rc.remove(subject, RepositoryProperties.Read, null, p.entryURI());
				rc.remove(subject, RepositoryProperties.Write, null, p.entryURI());
			}
			if (p.exists()) {
				context.addTombstone(p.entryURI(), rc);
			}
		}

		for (Map.Entry<EntryImpl, Set<URI>> prune : prunes.entrySet()) {
			if (((ListImpl) prune.getKey().getResource()).pruneExactly(rc, prune.getValue())) {
				prunedLists.add(prune.getKey());
			}
		}
		((EntryImpl) context.getEntry()).updateModifiedDateSynchronized(rc, rc.getValueFactory());
	}

	/**
	 * After the commit, still under the monitor: updates the context's indexes, flags the removed entries deleted, also
	 * the instance the soft cache holds, evicts it, and drops the members the pruned lists hold in memory. Cannot fail
	 * on an IRI {@code java.net.URI} rejects.
	 *
	 * @param dropIndexes whether to drop the context's indexes, so that the next read builds them from the store, as a
	 *                    caller that changed the index graph further needs; otherwise the removed entries are taken out
	 *                    of them, unless one could not be loaded or has such an IRI, which drops them too
	 */
	void publish(boolean dropIndexes) {
		if (dropIndexes || !unindexRemoved()) {
			context.unpublishIndexes();
		}
		SoftCache softCache = context.getSoftCache();
		for (Planned p : planned.values()) {
			Entry cached = softCache.getByEntryURI(URI.create(p.entryURI().stringValue()));
			if (cached instanceof EntryImpl cachedEntry) {
				cachedEntry.markDeleted();
				softCache.remove(cachedEntry);
			}
			if (p.entry() != null) {
				p.entry().markDeleted();
			}
		}
		for (EntryImpl list : prunedLists) {
			if (list.resource instanceof ListImpl listResource) {
				listResource.invalidateChildren();
			}
		}
	}

	/**
	 * Takes the removed entries out of the context's in-memory indexes, with the ops
	 * {@code ContextImpl.removeFromIndex} collects for one; {@link #apply} has removed their index triples.
	 *
	 * @return false, having changed nothing, if the indexes are not held in memory, where each op would only be
	 * buffered for a load, or if an entry could not be loaded or has an IRI {@code java.net.URI} rejects
	 */
	private boolean unindexRemoved() {
		if (!context.indexesPublished()) {
			return false;
		}
		List<ContextImpl.IndexOp> ops = new ArrayList<>();
		try {
			for (Planned p : planned.values()) {
				EntryImpl entry = p.entry();
				if (entry == null) {
					return false;
				}
				if (entry.getSesameExternalMetadataURI() != null) {
					ops.add(new ContextImpl.IndexOp(ContextImpl.IndexKind.EXTERNAL_METADATA, false,
							entry.getExternalMetadataURI(), entry.getEntryURI()));
				}
				if (entry.getSesameResourceURI() != null) {
					ops.add(new ContextImpl.IndexOp(ContextImpl.IndexKind.RESOURCE, false, entry.getResourceURI(),
							entry.getEntryURI()));
				}
			}
		} catch (IllegalArgumentException e) {
			return false;
		}
		context.applyIndexOps(ops);
		return true;
	}

	/**
	 * After {@link #publish}, without the monitor: fires the events and deletes the data files of the removed
	 * entries. Listener failures are isolated by the dispatch and a failed file deletion is logged, since the removal
	 * stands.
	 */
	void afterPublish() {
		for (Planned p : planned.values()) {
			if (p.entry() != null) {
				fire(p.entry(), RepositoryEvent.EntryDeleted);
				if (p.entry().getResource() instanceof ListImpl) {
					fire(p.entry(), RepositoryEvent.ResourceDeleted);
				}
			}
		}
		for (IRI member : unlinkedMembers) {
			try {
				Entry entry = context.getByEntryURI(URI.create(member.stringValue()));
				if (entry != null) {
					fire(entry, RepositoryEvent.EntryUpdated);
				}
			} catch (RuntimeException e) {
				log.error("Could not report the update of entry {}, which left a removed list", member, e);
			}
		}
		for (EntryImpl list : prunedLists) {
			fire(list, RepositoryEvent.ResourceUpdated);
		}
		for (Planned p : planned.values()) {
			if (p.entry() != null && p.entry().getResource() instanceof DataImpl data) {
				deleteFile(data);
			}
		}
	}

	/**
	 * Rolls the caller's transaction back and reloads from the store the context entry and the remaining lists,
	 * which {@link #apply} stamped modified in memory. Nothing else in memory changes before the commit.
	 */
	void rollBack(RepositoryConnection rc) {
		try {
			rc.rollback();
		} catch (RuntimeException e) {
			log.error("Failed to roll back the removal of entries of context {}", context.id, e);
		}
		List<EntryImpl> stamped = new ArrayList<>(prunes.keySet());
		stamped.add((EntryImpl) context.getEntry());
		for (EntryImpl entry : stamped) {
			try {
				entry.refreshFromRepository(rc);
			} catch (RuntimeException e) {
				log.error("Failed to refresh entry {} after a rollback, its modification date may be stale",
						entry.getEntryURI(), e);
			}
		}
	}

	/**
	 * @param rereading whether the entries are read again, after a relation copy may have been written into the
	 *                  relations graph of another planned entry, which the first read of that graph did not see
	 */
	private void read(RepositoryConnection reader, Collection<IRI> entryURIs, boolean rereading) {
		Map<IRI, Conventional> conventional = new LinkedHashMap<>();
		for (IRI entryURI : entryURIs) {
			conventionalGraphs(entryURI).ifPresentOrElse(graphs -> conventional.put(entryURI, graphs),
					() -> log.warn("Not removing {}, which is no entry of context {}", entryURI, context.id));
		}
		conventional.values().forEach(graphs -> plannedRelations.add(graphs.relations()));
		Map<IRI, List<Statement>> read = exactRemoval.read(reader,
				conventional.values().stream().flatMap(graphs -> graphs.all().stream()).toList());
		for (Map.Entry<IRI, Conventional> entry : conventional.entrySet()) {
			planned.put(entry.getKey(), toPlanned(reader, entry.getKey(), entry.getValue(), read, rereading));
		}
	}

	/** The graphs of an entry of this context as its URIs are made. */
	private Optional<Conventional> conventionalGraphs(IRI entryURI) {
		return URISplit.of(URI.create(entryURI.stringValue()), repositoryManager.getRepositoryURL())
				.filter(split -> split.getUriType() == URIType.MetaMetadata && context.id.equals(split.getContextId()))
				.map(split -> new Conventional(entryURI, iri(split.getMetadataURI().toString()),
						iri(split.getRelationURI().toString()), iri(split.getCachedExternalMetadataURI().toString()),
						iri(split.getResourceURI().toString())));
	}

	private Planned toPlanned(RepositoryConnection reader, IRI entryURI, Conventional conventional,
			Map<IRI, List<Statement>> read, boolean rereading) {
		EntryImpl entry = null;
		try {
			entry = context.entryFromGraph(entryURI, read.get(entryURI));
		} catch (CorruptEntryException e) {
			log.warn("Entry {} is corrupt, so its graphs are removed without its data file and events: {}",
					entryURI, e.getMessage());
		}
		if (entry != null) {
			refuseUnsupported(entry);
		}
		Map<IRI, List<Statement>> graphs = new LinkedHashMap<>();
		for (IRI graph : entry != null ? ownedGraphs(entry) : conventional.all()) {
			List<Statement> statements = read.get(graph);
			graphs.put(graph, statements != null ? statements : ExactRemoval.read(reader, graph, Integer.MAX_VALUE));
		}
		IRI relations = entry != null && entry.relationURI != null ? entry.relationURI : conventional.relations();

		Predicate<IRI> removedWithRelations = rereading ? object -> false
				: object -> exactRemoval.relationsGraphOf(object).map(plannedRelations::contains).orElse(false);
		List<Statement> copies = new ArrayList<>();
		for (Map.Entry<IRI, List<Statement>> graph : graphs.entrySet()) {
			boolean fromEntryGraph = graph.getKey().equals(entryURI);
			if (fromEntryGraph || isMetadataGraph(graph.getKey(), entry, conventional)) {
				copies.addAll(exactRemoval.relationCopies(reader, graph.getValue(), fromEntryGraph,
						removedWithRelations));
			}
		}

		List<URI> members = List.of();
		if (entry != null && entry.getResource() instanceof ListImpl list) {
			members = ListImpl.loadChildren(graphs.getOrDefault(list.getSesameURI(), List.of())).stream()
					.filter(member -> member != null).toList();
		}
		return new Planned(entryURI, entry, relations, listsHolding(entryURI, graphs.get(relations)), graphs, copies,
				members, aclSubjects(entryURI, entry, conventional));
	}

	/** The subjects the entry's ACL statements can have, as {@code EntryImpl} chooses them per access property. */
	private static Set<IRI> aclSubjects(IRI entryURI, EntryImpl entry, Conventional conventional) {
		Set<IRI> subjects = new LinkedHashSet<>(List.of(entryURI, conventional.metadata(), conventional.resource()));
		if (entry != null && entry.getSesameLocalMetadataURI() != null) {
			subjects.add(entry.getSesameLocalMetadataURI());
		}
		if (entry != null && entry.getSesameResourceURI() != null) {
			subjects.add(entry.getSesameResourceURI());
		}
		return subjects;
	}

	/** The graphs an entry owns according to its entry graph, as {@code EntryImpl.remove} clears them. */
	private static Set<IRI> ownedGraphs(EntryImpl entry) {
		Set<IRI> owned = new LinkedHashSet<>();
		owned.add(entry.getSesameEntryURI());
		EntryType type = entry.getEntryType();
		if ((type == EntryType.Local || type == EntryType.Link || type == EntryType.LinkReference)
				&& entry.getSesameLocalMetadataURI() != null) {
			owned.add(entry.getSesameLocalMetadataURI());
		}
		if ((type == EntryType.Reference || type == EntryType.LinkReference)
				&& entry.getSesameCachedExternalMetadataURI() != null) {
			owned.add(entry.getSesameCachedExternalMetadataURI());
		}
		if (entry.relationURI != null) {
			owned.add(entry.relationURI);
		}
		if (entry.getResource() instanceof RDFResource resource && resource.isRemovable()) {
			owned.add(resource.getSesameURI());
		}
		return owned;
	}

	private static boolean isMetadataGraph(IRI graph, EntryImpl entry, Conventional conventional) {
		if (entry == null) {
			return graph.equals(conventional.metadata()) || graph.equals(conventional.cachedExternalMetadata());
		}
		return graph.equals(entry.getSesameLocalMetadataURI())
				|| graph.equals(entry.getSesameCachedExternalMetadataURI());
	}

	/** By the resource, not the graph type, which a link or reference may declare without holding such a resource. */
	private static void refuseUnsupported(EntryImpl entry) {
		Object resource = entry.getResource();
		if (resource instanceof Context || resource instanceof User || resource instanceof Group) {
			throw new IllegalStateException("Entry " + entry.getEntryURI() + " of graph type " + entry.getGraphType()
					+ " cannot be purged");
		}
	}

	/** The resources of the lists that hold the entry, as its relations graph, read with it, records them. */
	private static Set<IRI> listsHolding(IRI entryURI, List<Statement> relations) {
		Set<IRI> lists = new HashSet<>();
		if (relations != null) {
			for (Statement statement : relations) {
				if (statement.getPredicate().equals(RepositoryProperties.hasListMember)
						&& statement.getObject().equals(entryURI) && statement.getSubject() instanceof IRI list) {
					lists.add(list);
				}
			}
		}
		return lists;
	}

	/**
	 * Derives what depends on all planned entries: which remaining lists lose which members, and the membership
	 * copies of the members a removed list leaves behind. Loads the remaining lists, which costs a scan for one that
	 * is not cached.
	 */
	private void link(RepositoryConnection reader) {
		prunes.clear();
		memberCopies.clear();
		unlinkedMembers.clear();
		for (Planned p : planned.values()) {
			for (IRI list : p.lists()) {
				listEntryOf(list).filter(listEntry -> !planned.containsKey(listEntry)).map(this::listEntry)
						.ifPresent(listEntry -> prunes.computeIfAbsent(listEntry, k -> new LinkedHashSet<>())
								.add(URI.create(p.entryURI().stringValue())));
			}
			if (p.entry() != null && p.entry().getResource() instanceof ListImpl list) {
				for (URI member : p.members()) {
					IRI memberIRI = iri(member.toString());
					if (!planned.containsKey(memberIRI)) {
						unlink(reader, list.getSesameURI(), memberIRI);
					}
				}
			}
		}
	}

	/** Records the membership copy of a member that stays, as {@code EntryImpl.removeReferringList} removes it. */
	private void unlink(RepositoryConnection reader, IRI listResource, IRI member) {
		unlinkedMembers.add(member);
		exactRemoval.relationsGraphOf(member).ifPresent(relations -> {
			if (reader.hasStatement(listResource, RepositoryProperties.hasListMember, member, false, relations)) {
				memberCopies.add(reader.getValueFactory().createStatement(listResource,
						RepositoryProperties.hasListMember, member, relations));
			}
		});
	}

	/** The entry URI of a list of this context named by its resource URI. */
	private Optional<IRI> listEntryOf(IRI listResource) {
		return URISplit.of(URI.create(listResource.stringValue()), repositoryManager.getRepositoryURL())
				.filter(split -> context.id.equals(split.getContextId())).flatMap(URISplit::getEntryURI)
				.map(entryURI -> iri(entryURI.toString()));
	}

	/** The remaining list entry, loaded once; null, and logged, if it is no local list of this context. */
	private EntryImpl listEntry(IRI listEntryURI) {
		if (listEntries.containsKey(listEntryURI)) {
			return listEntries.get(listEntryURI);
		}
		Entry entry = context.getByEntryURI(URI.create(listEntryURI.stringValue()));
		EntryImpl listEntry = entry instanceof EntryImpl impl && impl.getResource() instanceof ListImpl ? impl : null;
		if (listEntry == null) {
			log.warn("List {} holding a removed entry could not be resolved to a list, a dangling member reference "
					+ "may remain", listEntryURI);
		}
		listEntries.put(listEntryURI, listEntry);
		return listEntry;
	}

	private boolean isStale(RepositoryConnection rc, Planned p) {
		Value modified = p.modified();
		boolean modifiedKept = modified != null
				? rc.hasStatement(p.entryURI(), RepositoryProperties.Modified, modified, false, p.entryURI())
				: !rc.hasStatement(p.entryURI(), RepositoryProperties.Modified, null, false, p.entryURI());
		return !modifiedKept || !ExactRemoval.listsHolding(rc, p.entryURI(), p.relations()).equals(p.lists());
	}

	private void fire(Entry entry, RepositoryEvent event) {
		repositoryManager.fireRepositoryEvent(new RepositoryEventObject(entry, event));
	}

	private static void deleteFile(DataImpl data) {
		try {
			if (data.hasFile() && !data.deleteFile()) {
				log.warn("Failed to delete the data file of removed entry {}, truncating it instead",
						data.getEntry().getEntryURI());
				data.truncateFile();
			}
		} catch (RuntimeException e) {
			log.error("Failed to delete the data file of removed entry {}", data.getEntry().getEntryURI(), e);
		}
	}

	/** The graphs of an entry as its URIs are made: entry, metadata, relations, cached external metadata, resource. */
	private record Conventional(IRI entry, IRI metadata, IRI relations, IRI cachedExternalMetadata, IRI resource) {

		List<IRI> all() {
			return List.of(entry, metadata, relations, cachedExternalMetadata, resource);
		}
	}

	/**
	 * What removing one entry takes, as read.
	 *
	 * @param entry       the entry, or null if its entry graph is empty or corrupt
	 * @param relations   its relations graph
	 * @param lists       the resources of the lists holding it, as its relations graph records them
	 * @param graphs      the statements of each graph it owns, its entry graph among them
	 * @param copies      the relation copies its entry and metadata graphs put in other entries' relations graphs
	 * @param members     its members, if it is a list
	 * @param aclSubjects the subjects its ACL statements can have
	 */
	private record Planned(IRI entryURI, EntryImpl entry, IRI relations, Set<IRI> lists,
			Map<IRI, List<Statement>> graphs, List<Statement> copies, List<URI> members, Set<IRI> aclSubjects) {

		boolean exists() {
			return !entryGraph().isEmpty();
		}

		/** Its modified date as read, or null if it had none. */
		Value modified() {
			return entryGraph().stream()
					.filter(s -> s.getSubject().equals(entryURI) && s.getPredicate().equals(RepositoryProperties.Modified))
					.map(Statement::getObject).findFirst().orElse(null);
		}

		long statementCount() {
			return graphs.values().stream().mapToLong(List::size).sum();
		}

		private List<Statement> entryGraph() {
			return graphs.getOrDefault(entryURI, List.of());
		}
	}
}
