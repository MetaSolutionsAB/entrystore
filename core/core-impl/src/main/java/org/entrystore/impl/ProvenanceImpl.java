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

import com.google.common.collect.Lists;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.Entity;
import org.entrystore.GraphEntity;
import org.entrystore.Provenance;
import org.entrystore.ProvenanceType;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.OWL;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

/**
 * @author Matthias Palmér
 */
public class ProvenanceImpl implements Provenance {

    Log log = LogFactory.getLog(ProvenanceImpl.class);

    private EntryImpl entry;
    public ProvenanceImpl(EntryImpl entry) {
        this.entry = entry;
    }

    @Override
    public List<Entity> getEntities(ProvenanceType type) {
        RepositoryConnection rc = null;
        try {
            rc = this.entry.repository.getConnection();
            return getEntities(type, rc);
        } catch (RepositoryException e) {
            log.error(e.getMessage());
            return new ArrayList<Entity>();
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }

    public List<Entity> getEntities(ProvenanceType type, RepositoryConnection rc) throws RepositoryException {
        List<Entity> entities = new ArrayList<Entity>();
        RepositoryResult<Statement> latestStmt = rc.getStatements(null, OWL.SAMEAS, this.entry.getSesameLocalMetadataURI(), false, this.entry.entryURI);
        org.openrdf.model.URI latestURI = latestStmt.hasNext() ? (org.openrdf.model.URI) latestStmt.next().getSubject() : null;
		if (!latestStmt.isClosed()) {
			latestStmt.isClosed();
		}
		RepositoryResult<Statement> rr = rc.getStatements(null, RepositoryProperties.generatedAtTime, null, false, this.entry.entryURI);
		while (rr.hasNext()) {
            entities.add(new MetadataEntityImpl(this.entry, rr.next(), latestURI));
        }
        if (!rr.isClosed()) {
			rr.close();
		}
        entities.sort(new Comparator<Entity>() {
            public int compare(Entity t1, Entity t2) {
                Date d1 = t1.getGeneratedDate();
                Date d2 = t2.getGeneratedDate();
                if (d1.after(d2)) {
                    return 1;
                } else if (d1.before(d2)) {
                    return -1;
                }
                return 0;
            }
        });

        return entities;
    }


    @Override
    public Entity getEntityAt(Date date, ProvenanceType type) {
        List<Entity> list = Lists.reverse(this.getEntities(type));
        for (Entity e : list) {
            if (e.getGeneratedDate().before(date)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public Entity getEntityFor(URI uri) {
        RepositoryConnection rc = null;
        RepositoryResult<Statement> rr = null;
        try {
            rc = this.entry.repository.getConnection();
            rr = rc.getStatements(rc.getValueFactory().createURI(uri.toString()), RepositoryProperties.generatedAtTime, null, false, this.entry.entryURI);
            RepositoryResult<Statement> latestStmt = rc.getStatements(null, OWL.SAMEAS, this.entry.getSesameLocalMetadataURI(), false, this.entry.entryURI);
            org.openrdf.model.URI latestURI = latestStmt.hasNext() ? (org.openrdf.model.URI) latestStmt.next().getSubject() : null;
			if (latestStmt != null && !latestStmt.isClosed()) {
				latestStmt.close();
			}
            Entity entity = null;
            if (rr.hasNext()) {
                entity = new MetadataEntityImpl(this.entry, rr.next(), latestURI);
            }
            return entity;
        } catch (RepositoryException e) {
            log.error(e.getMessage());
            return null;
        } finally {
			try {
				if (rr != null && !rr.isClosed()) {
					rr.close();
				}
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
            try {
                if (rc != null) {
					rc.close();
				}
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }

    @Override
    public Entity getEntityFor(String revision, ProvenanceType type) {
        //Currently always for metadata provenance type.
        return getEntityFor(getRevisionURI(revision, ProvenanceType.Metadata));
    }

    private URI getNewRevisionURIFromOld(URI latestEntityURI, ProvenanceType type) {
        //Currently always for metadata provenance type.
        String lmu = this.entry.getLocalMetadataURI().toString();
        String oldRev = latestEntityURI.toString().substring(lmu.length()+5);
        String newRev = Integer.toString(Integer.parseInt(oldRev)+1);
        return getRevisionURI(newRev, type);
    }

    private URI getRevisionURI(String revision, ProvenanceType type) {
        //Currently only provenancetype metadata supported
        return URI.create(this.entry.getLocalMetadataURI().toString()+ "?rev="+revision);
    }

    @Override
    public GraphEntity addMetadataEntity(Graph oldgraph) {
        RepositoryConnection rc = null;
        try {
            rc = this.entry.repository.getConnection();
            return addMetadataEntity(oldgraph, rc);
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

    private org.openrdf.model.URI getUserURI(ValueFactory vf) {
        if (this.entry.repositoryManager != null &&
                this.entry.repositoryManager.getPrincipalManager() != null &&
                this.entry.repositoryManager.getPrincipalManager().getAuthenticatedUserURI() != null) {
            return vf.createURI(this.entry.repositoryManager.getPrincipalManager().getAuthenticatedUserURI().toString());
        }
        return null;
    }

    protected void storeProvenanceGraph(org.openrdf.model.URI ng, Graph graph) {
        try {
            RepositoryConnection rc = this.entry.repositoryManager.getProvenanceRepository().getConnection();
            rc.add(graph, ng);
        } catch (RepositoryException e) {
            log.error(e.getMessage());
        }
    }

    protected GraphEntity addMetadataEntity(Graph oldgraph, RepositoryConnection rc) throws RepositoryException {
        MetadataEntityImpl latestEntity = (MetadataEntityImpl) getEntityAt(new Date(), ProvenanceType.Metadata);
        ValueFactory vf = rc.getValueFactory();
        org.openrdf.model.URI attr = this.getUserURI(vf);
        if (attr == null) {
            return null;
        }
        org.openrdf.model.URI uri;
        org.openrdf.model.URI eURI = this.entry.entryURI;

        if (latestEntity == null) {
            uri = vf.createURI(getRevisionURI("1", ProvenanceType.Metadata).toString());
        } else {
            URI newMDURI = getNewRevisionURIFromOld(latestEntity.getURI(), ProvenanceType.Metadata);
            uri = vf.createURI(newMDURI.toString());
            rc.add(uri, RepositoryProperties.wasRevisionOf, latestEntity.getSesameURI(), eURI);
            rc.remove(rc.getStatements(latestEntity.getSesameURI(), OWL.SAMEAS, null, false, eURI), eURI);
            storeProvenanceGraph(latestEntity.getSesameURI(), oldgraph);
        }

        try {
            Literal modified = vf.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(new GregorianCalendar()));
            rc.add(uri, RepositoryProperties.wasAttributedTo, attr, eURI);
            rc.add(uri, RepositoryProperties.generatedAtTime, modified, eURI);
            rc.add(uri, OWL.SAMEAS, entry.getSesameLocalMetadataURI(), eURI);
            return new MetadataEntityImpl(this.entry, uri, modified.calendarValue().toGregorianCalendar().getTime(), true);
        } catch (DatatypeConfigurationException e) {
            log.error(e.getMessage());
            throw new RepositoryException(e.getMessage(), e);
        }
    }

    public void remove() {
        List<Entity> entities = getEntities(ProvenanceType.Metadata);
        RepositoryConnection rc = null;
        try {
            rc = this.entry.repositoryManager.getProvenanceRepository().getConnection();
            for (Entity e : entities) {
                ((MetadataEntityImpl) e).remove(rc);
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

    protected boolean hasProvenanceCharacter(Statement st) {
        org.openrdf.model.URI predicate = st.getPredicate();
        return RepositoryProperties.wasRevisionOf.equals(predicate) ||
                RepositoryProperties.generatedAtTime.equals(predicate) ||
                RepositoryProperties.wasAttributedTo.equals(predicate) ||
                (OWL.SAMEAS.equals(predicate) && entry.getSesameLocalMetadataURI().equals(st.getObject()));
    }

    protected Graph getMinimalGraph(RepositoryConnection rc) throws RepositoryException {
        RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, this.entry.entryURI);
        Graph result = new GraphImpl(this.entry.repository.getValueFactory());
        while (rr.hasNext()) {
            Statement st = rr.next();
            if (hasProvenanceCharacter(st)) {
                result.add(st);
            }
        }
        return result;
    }
}