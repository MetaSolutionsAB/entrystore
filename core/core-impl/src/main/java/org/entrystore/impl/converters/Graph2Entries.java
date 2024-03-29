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
	
	private static ValueFactory vf = SimpleValueFactory.getInstance();
	private static IRI mergeResourceId = vf.createIRI(NS.entrystore, "mergeResourceId");
	private static IRI referenceResourceId = vf.createIRI(NS.entrystore, "referenceResourceId");

	private static Logger log = LoggerFactory.getLogger(Graph2Entries.class);
	private Context context;
	
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
	 * @param graph the RDF to merge
	 * @param destinationEntryId an entryId who's resource (resourcetype Graph) the graph should be stored in, 
	 * if the id does not yet correspond to an existing entry it will be created. An empty string indicates that 
	 * a new entry should be created, null indicates that the graph should end up in multiple entries as 
	 * indicated in the graph via the mergeResourceId properties on blank nodes.
	 * @param destinationListURI a list where the destinationEntryId will be created if a new entry is to be created.
	 * @return a collection of the merged entries (updated or created), the referenced entries are not included in the collection.
	 */
	public Set<Entry> merge(Model graph, String destinationEntryId, URI destinationListURI) {
		log.info("About to update/create entries in context "+this.context.getEntry().getId()+".");
		Set<Entry> entries = new HashSet<Entry>();
		
		HashMap<String, Resource> newResources = new HashMap<String, Resource>();
		HashMap<String, Resource> oldResources = new HashMap<String, Resource>();
		HashMap<Resource, Resource> translate = new HashMap<Resource, Resource>();

		Iterator<Statement> stmts = graph.filter(null, referenceResourceId, null).iterator();
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			String entryId = statement.getObject().stringValue();
			Resource re = newResources.get(entryId);
			if (re == null) {
				URI uri = URISplit.fabricateURI(
						context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
						context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
				re = vf.createIRI(uri.toString());
			}
			translate.put(statement.getSubject(), re);
		}

		
		if (destinationEntryId != null) {
			URI uri = URISplit.fabricateURI(
					context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
					context.getEntry().getId(), RepositoryProperties.DATA_PATH, destinationEntryId);
			Resource newRe = vf.createIRI(uri.toString());

			stmts = graph.filter(null, mergeResourceId, null).iterator();
			while (stmts.hasNext()) {
				Statement statement = (Statement) stmts.next();
				translate.put(statement.getSubject(), newRe);
			}
			
			Entry entry;
			boolean entryCreated = false;
			if ("".equals(destinationEntryId)) {
				entry = this.context.createResource(null, GraphType.Graph, ResourceType.InformationResource, destinationListURI);
				entryCreated = true;
				((ContextImpl) this.context).setMetadata(entry, "RDF Graph created at "+new Date().toString(), null);
			} else {
				entry = this.context.get(destinationEntryId); //Try to fetch existing entry.
				if (entry == null) {
					entry = this.context.createResource(destinationEntryId, GraphType.Graph, ResourceType.InformationResource, destinationListURI);
					entryCreated = true;
				}				
			}
			Model resg = this.translate(graph, translate);
			((RDFResource) entry.getResource()).setGraph(resg);

			Model subg = this.extract(resg, newRe, new HashSet<Resource>(), new HashMap<Resource, Resource>());
			if (!subg.isEmpty()) {
				entry.getLocalMetadata().setGraph(subg);
			} else if (entryCreated) {
				((ContextImpl) this.context).setMetadata(entry, "RDF Graph created at "+new Date().toString(), null);				
			}
			
			entries.add(entry);
			return entries;
		}
		
		stmts = graph.filter(null, mergeResourceId, null).iterator();
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			String entryId = statement.getObject().stringValue();
			URI uri = URISplit.fabricateURI(
					context.getEntry().getRepositoryManager().getRepositoryURL().toString(), 
					context.getEntry().getId(), RepositoryProperties.DATA_PATH, entryId);
			Resource newRe = vf.createIRI(uri.toString());
			newResources.put(entryId, newRe);
			oldResources.put(entryId, statement.getSubject());
			translate.put(statement.getSubject(), newRe);
		}

		log.info("Found "+oldResources.size()+" resources that will be updated/created.");

		int newResCounter = 0;
		int updResCounter = 0;
		Collection<Resource> ignore = newResources.values();
		for (String entryId : newResources.keySet()) {
			Model subg = this.extract(graph, oldResources.get(entryId), ignore, translate);
			Entry entry = this.context.get(entryId); //Try to fetch existing entry.
			if (entry == null) {  //If none exist, create it.
				entry = this.context.createResource(entryId, GraphType.None, ResourceType.NamedResource, null);
				newResCounter++;
			} else {
				updResCounter++;
			}
			entry.getLocalMetadata().setGraph(subg);
			entries.add(entry);
		}
		log.info("Updated "+updResCounter+" existing entries and created "+ newResCounter+" new entries.");
		log.info("Finished updating/creating entries in context "+this.context.getEntry().getId()+".");		
		return entries;
	}
	
	/**
	 * Extracts a smaller graph by starting from a given resource and collects all direct and indirect outgoing triples, 
	 * only stopping when non-blank nodes or resources in the ignore set are encountered. It also replaces resources
	 * found in the translate map.
	 * 
	 * @param from the graph to extract triples from
	 * @param subject the resource to start detecting triples from
	 * @param ignore a set of resources that when encountered no further triples (outgoing) from that resource should be included
	 * @param translate a map of resources, the keys should be replaces with the values when encountered.
	 * @return the extracted subgraph, may be empty, not null.
	 */
	private Model extract(Model from, Resource subject, Collection<Resource> ignore, Map<Resource, Resource> translate) {
		Model to = new LinkedHashModel();
		HashSet<Resource> collected = new HashSet<Resource>(ignore);
		this._extract(from, to, subject, collected, translate);
		return to;
	}
	private void _extract(Model from, Model to, Resource subject, Set<Resource> collected, Map<Resource, Resource> translate) {
		Iterator<Statement> stmts = from.filter(subject, null, null).iterator();
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			Resource subj = statement.getSubject();
			IRI pred = statement.getPredicate();
			Value obj = statement.getObject();
			//Recursive step.
			if (obj instanceof BNode && !collected.contains(obj)) {
				collected.add((BNode) obj);
				this._extract(from, to, (BNode) obj, collected, translate);
			}

			if (pred.equals(mergeResourceId) || pred.equals(referenceResourceId)) {
				continue;
			}

			if (translate.get(subj) != null) {
				subj = translate.get(subj);
			}
			if (translate.get(obj) != null) {
				obj = translate.get(obj);
			}
			to.add(subj, pred, obj);
		}
	}
	private Model translate(Model from, Map<Resource, Resource> translate) {
		Model to = new LinkedHashModel();
		for (Statement statement : from) {
			Resource subj = statement.getSubject();
			IRI pred = statement.getPredicate();
			Value obj = statement.getObject();
			if (pred.equals(mergeResourceId) || pred.equals(referenceResourceId)) {
				continue;
			}

			if (translate.get(subj) != null) {
				subj = translate.get(subj);
			}
			if (translate.get(obj) != null) {
				obj = translate.get(obj);
			}
			to.add(subj, pred, obj);
		}
		
		return to;		
	}
}