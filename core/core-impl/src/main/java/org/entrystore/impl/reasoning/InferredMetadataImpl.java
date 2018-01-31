package org.entrystore.impl.reasoning;

import org.entrystore.Entry;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.EntryImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.util.URISplit;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;


/**
 * Created by matthias on 2018-01-18.
 */
public class InferredMetadataImpl implements Metadata {
    private static Logger log = LoggerFactory.getLogger(InferredMetadataImpl.class);

    private final EntryImpl entry;
    private final ReasoningManagerImpl manager;
    private final RepositoryManagerImpl rm;

    public InferredMetadataImpl(ReasoningManagerImpl manager, RepositoryManagerImpl rm, EntryImpl
            entry) {
        this.manager = manager;
        this.rm = rm;
        this.entry = entry;
    }

    @Override
    public java.net.URI getResourceURI() {
        return this.entry.getResourceURI();
    }

    @Override
    public java.net.URI getURI() {
        return URISplit.fabricateURI(rm.getRepositoryURL().toString(),
                this.entry.getContext().getEntry().getId(),
                RepositoryProperties.INFERRED,
                this.entry.getId());
    }

    @Override
    public Graph getGraph() {
        Repository repository = manager.getRepository();
        PrincipalManager pm = rm.getPrincipalManager();
        if (pm != null) {
            pm.checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.ReadMetadata);
        }
        RepositoryConnection rc = null;
        try {
            rc = repository.getConnection();
            RepositoryResult<Statement> rr = rc.getStatements(null, null, null, false, ((EntryImpl) entry).getSesameEntryURI());
            if (!rr.hasNext()) {
                return null;
            }
            return new GraphImpl(repository.getValueFactory(), rr.asList());
        } catch (RepositoryException e) {
            log.error(e.getMessage());
            throw new org.entrystore.repository.RepositoryException("Failed to connect to reasoning repository.", e);
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }

    @Override
    public void setGraph(Graph graph) {
        throw new UnsupportedOperationException("Set graph not supported on inferred metadata");
    }

    @Override
    public boolean isCached() {
        return true;
    }

    private void addStmts(Graph source, Graph dest, TreeIndexImpl ti) {
        for (Statement stmt: source) {
            Value obj = stmt.getObject();
            if (obj instanceof org.openrdf.model.URI) {
                String node = obj.stringValue();
                TreeIndexContext tic = ti.getTree(node);
                if (tic != null) {
                    Set<String> ancestors = tic.ancestors(node);
                    if (ancestors != null) {
                        for(String ancestor: ancestors) {
                            dest.add(stmt.getSubject(),
                                    stmt.getPredicate(),
                                    dest.getValueFactory().createURI(ancestor));
                        }
                    }
                }
            }
        }
    }
    public Graph update() {
        RepositoryConnection rc = null;
        try {
            rc = manager.getRepository().getConnection();
            return this.update(rc, true);
        } catch (RepositoryException e) {
            log.error(e.getMessage());
            throw new org.entrystore.repository.RepositoryException("Failed to connect to reasoning repository.", e);
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }

    public Graph update(RepositoryConnection rc, boolean clear) throws RepositoryException {
        GraphImpl graph = new GraphImpl(this.manager.getRepository().getValueFactory());
        addStmts(entry.getMetadataGraphExceptInferred(), graph,
                ((TreeIndexImpl) manager.getTreeIndex()));
        URI entryURI = rc.getValueFactory().createURI(entry.getEntryURI().toString());
        if (clear) {
            rc.clear(entryURI);
        }
        if (!graph.isEmpty()) {
            rc.add(graph, entryURI);
        }
        this.rm.fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.InferredMetadataUpdated, graph));
        return graph;
    }

    public void remove() {
        PrincipalManager pm = rm.getPrincipalManager();
        if (pm != null) {
            pm.checkAuthenticatedUserAuthorized(entry, PrincipalManager.AccessProperty.ReadMetadata);
        }
        Repository repository = manager.getRepository();
        RepositoryConnection rc = null;
        try {
            rc = repository.getConnection();
            rc.clear(((EntryImpl) entry).getSesameEntryURI());
        } catch (RepositoryException e) {
            log.error(e.getMessage());
            throw new org.entrystore.repository.RepositoryException("Failed to connect to reasoning repository.", e);
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }
}