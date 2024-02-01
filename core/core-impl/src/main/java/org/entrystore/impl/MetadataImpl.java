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
	private boolean cached;
	private boolean localCache;
	Logger log = LoggerFactory.getLogger(MetadataImpl.class);

	public MetadataImpl(EntryImpl entry, IRI uri, IRI resourceUri, boolean cached) {
		this.entry = entry;
		this.uri = uri;
		this.resourceUri = resourceUri;
		this.mdContext = uri;
		this.cached = cached;
		this.localCache = true; //TODO fix
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
				RepositoryConnection rc = this.entry.repository.getConnection();
				rc.begin();
				try {
					Model oldGraph = removeGraphSynchronized(rc);
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
				} catch (AuthorizationException ae) {
					rc.rollback();
					log.warn(ae.getMessage());
					throw ae;
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
	public Model removeGraphSynchronized(RepositoryConnection rc) throws RepositoryException {
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
						sourceEntry.removeRelationSynchronized(statement, rc, this.entry.repository.getValueFactory());
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
