/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.impl.converters;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.URISplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * Converts an RDF graph to a set of Entries
 *
 * @author Matthias Palmér
 */
public class Graph2Entries {

	private static final ValueFactory valueFactory = SimpleValueFactory.getInstance();
	private static final IRI mergeResourceId = valueFactory.createIRI(NS.entrystore, "mergeResourceId");
	private static final IRI referenceResourceId = valueFactory.createIRI(NS.entrystore, "referenceResourceId");

	private static final Logger log = LoggerFactory.getLogger(Graph2Entries.class);
	private final Context context;

	public Graph2Entries(Context context) {
		this.context = context;
	}

	/**
	 * Detects and adds a set of entries from the graph via the anonymous closure algorithm starting from resources
	 * indicated with either of the two following properties that both indicate which entryId to use:<ul>
	 * <li>http://entrystore.org/terms/mergeResourceId or the</li>
	 * <li>http://entrystore.org/terms/referenceResourceId</li>
	 * </ul>
	 * The mergeResourceId indicates that the corresponding entry should be merged or created if it does not exist.
	 * The referenceResourceId only indicates that the relevant resource should be referenced.
	 *
	 * @param graph              the RDF to merge
	 * @param destinationEntryId an entryId whose resource (resourcetype Graph) the graph should be stored in.
	 *                           A: If the id does not yet correspond to an existing entry, it will be created.
	 *                           B: An empty string indicates that a new entry should be created,
	 *                           C: null indicates that the graph should end up in multiple entries as indicated
	 *                           in the graph via the mergeResourceId properties on blank nodes.
	 * @param destinationListURI a list where the destinationEntryId will be created if a new entry is to be created.
	 * @return a collection of the merged entries (updated or created), the referenced entries are not included in the collection.
	 */
	public Set<Entry> merge(Model graph, String destinationEntryId, URI destinationListURI) {
		if (graph == null) {
			log.info("Supplied null instead of a graph.");
			return null;
		}

		log.info("About to update/create entries in context {}.", this.context.getEntry().getId());
		Set<Entry> entries = new HashSet<>();

		HashMap<String, Resource> newResources = new HashMap<>();
		HashMap<String, Resource> oldResources = new HashMap<>();
		HashMap<Value, Resource> translate = new HashMap<>();

		Iterator<Statement> statements = graph.filter(null, referenceResourceId, null).iterator();

		// populate the translate Map with entries from statements
		while (statements.hasNext()) {
			Statement statement = statements.next();
			String entryId = statement.getObject().stringValue();
			Resource newResource = newResources.get(entryId);
			if (newResource == null) {
				URI uri = URISplit.createURI(
					context.getEntry().getRepositoryManager().getRepositoryURL().toString(),
					context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
				newResource = valueFactory.createIRI(uri.toString());
			}
			translate.put(statement.getSubject(), newResource);
		}

		if (destinationEntryId != null) {
			URI uri = URISplit.createURI(
				context.getEntry().getRepositoryManager().getRepositoryURL().toString(),
				context.getEntry().getId(), RepositoryProperties.DATA_PATH, destinationEntryId);
			Resource newResource = valueFactory.createIRI(uri.toString());

			statements = graph.filter(null, mergeResourceId, null).iterator();
			while (statements.hasNext()) {
				Statement statement = statements.next();
				translate.put(statement.getSubject(), newResource);
			}

			Entry entry;
			boolean entryCreated = false;

			if (destinationEntryId.isEmpty()) {
				entry = this.context.createResource(null, GraphType.Graph, ResourceType.InformationResource, destinationListURI);
				entryCreated = true;
				((ContextImpl) this.context).setMetadata(entry, "RDF Graph created at " + new Date(), null);
			} else {
				entry = this.context.get(destinationEntryId); //Try to fetch existing entry.
				if (entry == null) {
					entry = this.context.createResource(destinationEntryId, GraphType.Graph, ResourceType.InformationResource, destinationListURI);
					entryCreated = true;
				}
			}

			Model resourceGraph = this.translate(graph, translate);
			((RDFResource) entry.getResource()).setGraph(resourceGraph);

			Model subGraph = this.extract(resourceGraph, newResource, new HashSet<>(), new HashMap<>());
			if (!subGraph.isEmpty()) {
				entry.getLocalMetadata().setGraph(subGraph);
			} else if (entryCreated) {
				((ContextImpl) this.context).setMetadata(entry, "RDF Graph created at " + new Date(), null);
			}

			entries.add(entry);
			return entries;
		}

		statements = graph.filter(null, mergeResourceId, null).iterator();
		while (statements.hasNext()) {
			Statement statement = statements.next();
			String entryId = statement.getObject().stringValue();
			URI uri = URISplit.createURI(
				context.getEntry().getRepositoryManager().getRepositoryURL().toString(),
				context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
			Resource newRe = valueFactory.createIRI(uri.toString());
			newResources.put(entryId, newRe);
			oldResources.put(entryId, statement.getSubject());
			translate.put(statement.getSubject(), newRe);
		}

		log.info("Found {} resources that will be updated/created.", oldResources.size());

		int newResCounter = 0;
		int updResCounter = 0;
		Collection<Resource> ignore = newResources.values();
		for (String entryId : newResources.keySet()) {
			Model subGraph = this.extract(graph, oldResources.get(entryId), ignore, translate);
			Entry entry = this.context.get(entryId); // Try to fetch existing entry.
			if (entry == null) {  // If none exists, create it.
				entry = this.context.createResource(entryId, GraphType.None, ResourceType.NamedResource, null);
				newResCounter++;
			} else {
				updResCounter++;
			}
			entry.getLocalMetadata().setGraph(subGraph);
			entries.add(entry);
		}
		log.info("Updated {} existing entries and created {} new entries.", updResCounter, newResCounter);
		log.info("Finished updating/creating entries in context {}.", this.context.getEntry().getId());
		return entries;
	}

	private boolean checkPredicate(IRI predicate) {
		return !mergeResourceId.equals(predicate) && !referenceResourceId.equals(predicate);
	}

	private void populateModel(Model model, Statement statement, Map<Value, Resource> translate) {
		Resource subject = statement.getSubject();
		IRI predicate = statement.getPredicate();
		Value object = statement.getObject();

		if (translate.get(subject) != null) {
			subject = translate.get(subject);
		}
		if (translate.get(object) != null) {
			object = translate.get(object);
		}
		model.add(subject, predicate, object);
	}

	private Model translate(Model from, Map<Value, Resource> translate) {
		Model to = new LinkedHashModel();
		for (Statement statement : from) {
			if (checkPredicate(statement.getPredicate())) {
				populateModel(to, statement, translate);
			}
		}

		return to;
	}

	/**
	 * Extracts a smaller graph by starting from a given resource and collects all direct and indirect outgoing triples,
	 * only stopping when non-blank nodes or resources in the "ignore" set are encountered. It also replaces resources
	 * found in the "translate" map.
	 *
	 * @param from      the graph to extract triples from
	 * @param subject   the resource to start detecting triples from
	 * @param ignore    a set of resources that when encountered no further triples (outgoing) from that resource should be included
	 * @param translate a map of resources, the keys should be replaced with the values when encountered.
	 * @return the extracted subgraph may be empty, not null.
	 */
	private Model extract(Model from, Resource subject, Collection<Resource> ignore, Map<Value, Resource> translate) {
		Model to = new LinkedHashModel();
		HashSet<Resource> collected = new HashSet<>(ignore);
		this._extract(from, to, subject, collected, translate);
		return to;
	}

	private void _extract(Model from, Model to, Resource resource, Set<Resource> collected, Map<Value, Resource> translate) {
		for (Statement statement : from.filter(resource, null, null)) {
			Value object = statement.getObject();
			// Recursive step.
			if (object instanceof BNode && !collected.contains((Resource) object)) {
				collected.add((BNode) object);
				this._extract(from, to, (BNode) object, collected, translate);
			}

			if (checkPredicate(statement.getPredicate())) {
				populateModel(to, statement, translate);
			}
		}
	}

}
