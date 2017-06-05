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

import java.net.URI;
import java.util.Iterator;

import javax.xml.datatype.DatatypeConfigurationException;

import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.PrincipalManager.AccessProperty;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MetadataImpl implements Metadata {

	private EntryImpl entry;
	private org.openrdf.model.URI uri;
	private org.openrdf.model.URI resourceUri;
	private org.openrdf.model.Resource mdContext;
	private boolean cached;
	private boolean localCache;
	Logger log = LoggerFactory.getLogger(MetadataImpl.class);

	public MetadataImpl(EntryImpl entry, org.openrdf.model.URI uri, org.openrdf.model.URI resourceUri, boolean cached) {
		this.entry = entry;
		this.uri = uri;
		this.resourceUri = resourceUri;
		this.mdContext = uri;
		this.cached = cached;
		this.localCache = true; //TODO fix
	}

	public Graph getGraph() {
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
			RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, mdContext);
			Graph result = new GraphImpl(this.entry.repository.getValueFactory(), rr.asList());
			return result;
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

	public void setGraph(Graph graph) {
		PrincipalManager pm = this.entry.getRepositoryManager().getPrincipalManager();
		if (pm != null) {
			pm.checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteMetadata);
		}
		
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					Graph oldGraph = removeGraphSynchronized(rc);
					addGraphSynchronized(rc, graph);
					ProvenanceImpl provenance = (ProvenanceImpl) this.entry.getProvenance();
					if (provenance != null && !cached) {
						provenance.addMetadataEntity(oldGraph, rc);
					}
					rc.commit();
					if (cached) {
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ExternalMetadataUpdated, graph));
					} else {
						entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.MetadataUpdated, graph));
					}
				
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
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}
	public Graph removeGraphSynchronized(RepositoryConnection rc) throws RepositoryException {
		String base = this.entry.repositoryManager.getRepositoryURL().toString();
		//Fetch old graph
		RepositoryResult<Statement> iter = rc.getStatements(null, null, null, false, mdContext);
		GraphImpl graph = new GraphImpl(rc.getValueFactory(), iter.asList());

		// Remove relations in other entries inverse relational cache if entry has repository URL.
		if (this.resourceUri.stringValue().startsWith(base)) { //Only check for relations for non external links at this point.
			Iterator<Statement> iter2 = graph.iterator();
 			while(iter2.hasNext()) {
				Statement statement = iter2.next();
				Value obj = statement.getObject();
				Resource subj = statement.getSubject();
				//Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
				if (obj instanceof org.openrdf.model.URI 
					&& obj.stringValue().startsWith(base)
					&& subj.stringValue().startsWith(base)) {
					URI entryURI = URI.create(statement.getObject().stringValue()); 

					EntryImpl sourceEntry =  (EntryImpl)this.entry.getRepositoryManager().getContextManager().getEntry(entryURI); 
					if (sourceEntry != null) {
					    sourceEntry.removeRelationSynchronized(statement, rc, this.entry.repository.getValueFactory());
					}
				}
			}
		}
		rc.clear(mdContext);
		return graph;
	}
	
	public void addGraphSynchronized(RepositoryConnection rc, Graph graph) throws RepositoryException, DatatypeConfigurationException {
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
			Iterator<Statement> iter = graph.iterator(); 
			while(iter.hasNext()) {
				Statement statement = iter.next();
				Value obj = statement.getObject();
				Resource subj = statement.getSubject();
				//Check for relations between this resource and another entry (resourceURI (has to be a repository resource), metadataURI, or entryURI)
				if (obj instanceof org.openrdf.model.URI 
					&& obj.stringValue().startsWith(base)
					&& subj.stringValue().startsWith(base)) {
					URI entryURI = URI.create(statement.getObject().stringValue()); 

					EntryImpl sourceEntry =  (EntryImpl)this.entry.getRepositoryManager().getContextManager().getEntry(entryURI);
                    if (sourceEntry != null) {
                        sourceEntry.addRelationSynchronized(statement, rc, this.entry.repository.getValueFactory());
                    }
				}
			}
		}
	}

	public boolean isCached() {
		return cached;
	}

}
