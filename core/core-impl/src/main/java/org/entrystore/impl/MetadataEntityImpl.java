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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.GraphEntity;
import org.entrystore.ProvenanceType;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import java.net.URI;
import java.util.Date;

/**
 * @author Matthias Palmér
 */
public class MetadataEntityImpl implements GraphEntity {
    private org.openrdf.model.URI uri;
    private EntryImpl entry;
    private Date date;
    private org.openrdf.model.URI attributedURI;
    private boolean latest;

    Log log = LogFactory.getLog(MetadataEntityImpl.class);

    public MetadataEntityImpl(EntryImpl entry, org.openrdf.model.URI uri, Date date, boolean isLatest) {
        this.entry = entry;
        this.uri = uri;
        this.date = date;
        this.latest = isLatest;
    }

    public MetadataEntityImpl(EntryImpl entry, Statement generatedStmt, org.openrdf.model.URI latestEntityURI) {
        this(entry, (org.openrdf.model.URI) generatedStmt.getSubject(),
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

    protected org.openrdf.model.URI getSesameURI() {
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
                    this.attributedURI = (org.openrdf.model.URI) attr.next().getObject();
                }
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
                RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, uri);
                return new GraphImpl(rc.getValueFactory(), rr.asList());
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