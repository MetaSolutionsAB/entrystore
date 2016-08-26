/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import org.entrystore.PrincipalManager.AccessProperty;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RDFResource extends ResourceImpl {
	
	Logger log = LoggerFactory.getLogger(RDFResource.class);
	
	protected RDFResource(EntryImpl entry, String resourceURI) {
		super(entry, resourceURI);
	}

	public RDFResource(EntryImpl entry, org.openrdf.model.URI resourceURI) {
		super(entry, resourceURI);
	}

	public Graph getGraph() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		RepositoryConnection rc = null; 
		try {
			rc = this.entry.repository.getConnection();
			RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, resourceURI);
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

	public void setGraph(Graph graph) {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);
		try {
			synchronized (this.entry.repository) {
				RepositoryConnection rc = this.entry.repository.getConnection();
				rc.setAutoCommit(false);
				try {
					rc.clear(resourceURI);
					rc.add(graph, resourceURI);
                    ((EntryImpl) this.entry).updateModifiedDateSynchronized(rc, this.entry.repository.getValueFactory());
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
			throw new org.entrystore.repository.RepositoryException("Failed to connect to Repository.", e);
		}
	}
	
	@Override
	public void remove(RepositoryConnection rc) throws Exception {
		synchronized (this.entry.repository) {
			rc.clear(this.resourceURI);	
		}
	}

}