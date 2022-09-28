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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.GraphEntity;
import org.entrystore.ProvenanceType;

import java.net.URI;
import java.util.Date;

/**
 * @author Matthias Palmér
 */
public class MetadataEntityImpl implements GraphEntity {
    private IRI uri;
    private EntryImpl entry;
    private Date date;
    private IRI attributedURI;
    private boolean latest;

    Log log = LogFactory.getLog(MetadataEntityImpl.class);

    public MetadataEntityImpl(EntryImpl entry, IRI uri, Date date, boolean isLatest) {
        this.entry = entry;
        this.uri = uri;
        this.date = date;
        this.latest = isLatest;
    }

    public MetadataEntityImpl(EntryImpl entry, Statement generatedStmt, IRI latestEntityURI) {
        this(entry, (IRI) generatedStmt.getSubject(),
                ((Literal) generatedStmt.getObject()).calendarValue().toGregorianCalendar().getTime(),
                generatedStmt.getSubject().equals(latestEntityURI));
    }

    @Override
    public URI getResourceURI() {
        return this.entry.getResourceURI();
    }

    @Override
    public URI getURI() {
        return URI.create(uri.stringValue());
    }

    protected IRI getSesameURI() {
        return uri;
    }

    @Override
    public Date getGeneratedDate() {
        return date;
    }

    @Override
    public URI getAttributedURI() {
        if (this.attributedURI == null) {
            RepositoryConnection rc = null;
            try {
                rc = this.entry.repository.getConnection();
                RepositoryResult<Statement> attr = rc.getStatements(this.uri, RepositoryProperties.wasAttributedTo, null, false, this.entry.entryURI);
                if (attr.hasNext()) {
                    this.attributedURI = (IRI) attr.next().getObject();
                }
                attr.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            } finally {
                try {
                    rc.close();
                } catch (RepositoryException e) {
                    log.error(e.getMessage());
                }
            }
        }
        return URI.create(this.attributedURI.stringValue());
    }

    @Override
    public ProvenanceType getProvenanceType() {
        return ProvenanceType.Metadata;
    }

    @Override
    public Graph getGraph() {
        if (this.latest) {
            return this.entry.getMetadataGraph();
        } else {
            RepositoryConnection rc = null;
            try {
                rc = this.entry.repositoryManager.getProvenanceRepository().getConnection();
                return Iterations.addAll(rc.getStatements(null, null, null, false, uri), new LinkedHashModel());
            } catch (RepositoryException e) {
                log.error(e.getMessage());
                return null;
            } finally {
                try {
                    rc.close();
                } catch (RepositoryException e) {
                    log.error(e.getMessage());
                }
            }
        }
    }

    @Override
    public void setGraph(Graph graph) {
        //Currently no need to rewrite history.
        throw new UnsupportedOperationException("Changing metadata history is not supported by this instance");
    }

    @Override
    public boolean isCached() {
        return false;
    }

    public void remove(RepositoryConnection rc) throws RepositoryException {
        if (!this.latest) {
            rc.clear(this.uri);
        }
    }
}