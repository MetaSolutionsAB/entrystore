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
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.Entity;
import org.entrystore.GraphEntity;
import org.entrystore.Provenance;
import org.entrystore.ProvenanceType;

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
            rc.begin();
            List<Entity> result = getEntities(type, rc);
            rc.commit();
            return result;
        } catch (RepositoryException e) {
            if (rc != null) {
                try {
                    rc.rollback();
                } catch (RepositoryException e1) {
                    log.error(e1.getMessage());
                }
            }
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
        return new ArrayList<Entity>();
    }

    public List<Entity> getEntities(ProvenanceType type, RepositoryConnection rc) throws RepositoryException {
        List<Entity> entities = new ArrayList<Entity>();
        RepositoryResult<Statement> latestStmt = null;
        IRI latestURI = null;
        try {
            latestStmt = rc.getStatements(null, OWL.SAMEAS, this.entry.getSesameLocalMetadataURI(), false, this.entry.entryURI);
            latestURI = latestStmt.hasNext() ? (IRI) latestStmt.next().getSubject() : null;
        } finally {
            if (latestStmt != null && !latestStmt.isClosed()) {
                latestStmt.close();
            }
        }

		RepositoryResult<Statement> rr = null;
        try {
            rr = rc.getStatements(null, RepositoryProperties.generatedAtTime, null, false, this.entry.entryURI);
            while (rr.hasNext()) {
                entities.add(new MetadataEntityImpl(this.entry, rr.next(), latestURI));
            }
        } finally {
            if (rr != null && !rr.isClosed()) {
                rr.close();
            }
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
		RepositoryResult<Statement> latestStmt = null;
		try {
            rc = this.entry.repository.getConnection();
            rr = rc.getStatements(rc.getValueFactory().createIRI(uri.toString()), RepositoryProperties.generatedAtTime, null, false, this.entry.entryURI);
            latestStmt = rc.getStatements(null, OWL.SAMEAS, this.entry.getSesameLocalMetadataURI(), false, this.entry.entryURI);
            IRI latestURI = latestStmt.hasNext() ? (IRI) latestStmt.next().getSubject() : null;
            Entity entity = null;
            if (rr.hasNext()) {
                entity = new MetadataEntityImpl(this.entry, rr.next(), latestURI);
            }
            return entity;
        } catch (RepositoryException e) {
            log.error(e.getMessage());
        } finally {
            if (rr != null && !rr.isClosed()) {
                try {
                    rr.close();
                } catch (RepositoryException e) {
                    log.error(e.getMessage());
                }
            }
            if (latestStmt != null && !latestStmt.isClosed()) {
            	try {
            		latestStmt.close();
				} catch (RepositoryException e) {
            		log.error(e.getMessage());
				}
			}
            if (rc != null) {
                try {
                    rc.close();
                } catch (RepositoryException e) {
                    log.error(e.getMessage());
                }
            }
        }
        return null;
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
    public GraphEntity addMetadataEntity(Model oldgraph) {
        RepositoryConnection rc = null;
        try {
            rc = this.entry.repository.getConnection();
            rc.begin();
            GraphEntity result = addMetadataEntity(oldgraph, rc);
            rc.commit();
            return result;
        } catch (RepositoryException e) {
            if (rc != null) {
                try {
                    rc.rollback();
                } catch (RepositoryException e1) {
                    log.error(e1.getMessage());
                }
            }
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
        return null;
    }

    private IRI getUserURI(ValueFactory vf) {
        if (this.entry.repositoryManager != null &&
                this.entry.repositoryManager.getPrincipalManager() != null &&
                this.entry.repositoryManager.getPrincipalManager().getAuthenticatedUserURI() != null) {
            return vf.createIRI(this.entry.repositoryManager.getPrincipalManager().getAuthenticatedUserURI().toString());
        }
        return null;
    }

    protected void storeProvenanceGraph(IRI ng, Model graph) {
        RepositoryConnection rc = null;
        try {
            rc = this.entry.repositoryManager.getProvenanceRepository().getConnection();
            rc.begin();
            rc.add(graph, ng);
            rc.commit();
        } catch (RepositoryException e) {
            if (rc != null) {
                try {
                    rc.rollback();
                } catch (RepositoryException e1) {
                    log.error(e.getMessage());
                }
            }
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

    protected GraphEntity addMetadataEntity(Model oldgraph, RepositoryConnection rc) throws RepositoryException {
        MetadataEntityImpl latestEntity = (MetadataEntityImpl) getEntityAt(new Date(), ProvenanceType.Metadata);
        ValueFactory vf = rc.getValueFactory();
        IRI attr = this.getUserURI(vf);
        if (attr == null) {
            return null;
        }
        IRI uri;
        IRI eURI = this.entry.entryURI;

        if (latestEntity == null) {
            uri = vf.createIRI(getRevisionURI("1", ProvenanceType.Metadata).toString());
        } else {
            URI newMDURI = getNewRevisionURIFromOld(latestEntity.getURI(), ProvenanceType.Metadata);
            uri = vf.createIRI(newMDURI.toString());
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
            rc.begin();
            for (Entity e : entities) {
                ((MetadataEntityImpl) e).remove(rc);
            }
            rc.commit();
        } catch (RepositoryException e) {
            if (rc != null) {
                try {
                    rc.rollback();
                } catch (RepositoryException e1) {
                    log.error(e1.getMessage());
                }
            }
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

    protected boolean hasProvenanceCharacter(Statement st) {
        IRI predicate = st.getPredicate();
        return RepositoryProperties.wasRevisionOf.equals(predicate) ||
                RepositoryProperties.generatedAtTime.equals(predicate) ||
                RepositoryProperties.wasAttributedTo.equals(predicate) ||
                (OWL.SAMEAS.equals(predicate) && entry.getSesameLocalMetadataURI().equals(st.getObject()));
    }

    protected Model getMinimalGraph(RepositoryConnection rc) throws RepositoryException {
        RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, this.entry.entryURI);
        Model result = new LinkedHashModel();
        while (rr.hasNext()) {
            Statement st = rr.next();
            if (hasProvenanceCharacter(st)) {
                result.add(st);
            }
        }
        rr.close();
        return result;
    }

}