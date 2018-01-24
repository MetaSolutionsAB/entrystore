package org.entrystore.impl.reasoning;

import com.google.common.collect.Queues;
import org.entrystore.*;
import org.entrystore.config.Config;
import org.entrystore.impl.EntryImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.reasoning.ReasoningManager;
import org.entrystore.reasoning.TreeChange;
import org.entrystore.reasoning.TreeIndex;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.URISplit;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.RDFS;
import org.openrdf.model.vocabulary.SKOS;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.memory.MemoryStore;
import org.openrdf.sail.nativerdf.NativeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;


/**
 * Created by matthias on 2018-01-09.
 */
public class ReasoningManagerImpl implements ReasoningManager {

    private static Logger log = LoggerFactory.getLogger(ReasoningManagerImpl.class);
    private static final int BATCH_SIZE = 100;
    private GraphInferencer job = null;

    private RepositoryManagerImpl rm;
    private Repository repository;

    private RepositoryListener updateListener = null;
    private RepositoryListener removeListener = null;


    private TreeIndexImpl tii = new TreeIndexImpl();
    private HashSet<String> contexts = new HashSet<>();
    private final Queue<TreeChange> changeQueue = Queues.newConcurrentLinkedQueue();

    public class GraphInferencer extends Thread {
        ReasoningManagerImpl rm;

        GraphInferencer(ReasoningManagerImpl rm) {
            this.rm = rm;
        }

        @Override
        public void run() {
            while (!interrupted()) {
                int batchCount = 0;
                HashSet<String> inferredURIs = new HashSet<>();
                HashSet<String> origURIs = new HashSet<>();
                if (changeQueue.size() > 0) {

                    synchronized (changeQueue) {
                        while (batchCount < BATCH_SIZE) {
                            TreeChange ce = changeQueue.poll();
                            switch (ce.getType()) {
                                case AddTo:
                                case AddAll:
                                case RemoveFrom:
                                    origURIs.addAll(ce.getNodes());
                                    break;
                                case Remove:
                                case RemoveAll:
                                    inferredURIs.addAll(ce.getNodes());
                            }
                            batchCount++;
                        }
                    }

                    if (batchCount > 0) {
                        HashSet<String> entryURIs = new HashSet<>();
                        RepositoryConnection rc = null;
                        URL base = rm.rm.getRepositoryURL();
                        // Check after affected entrys via references in inferrence graphs
                        try {
                            rc = rm.repository.getConnection();
                            ValueFactory vf = rc.getValueFactory();
                            for (String uri : inferredURIs) {
                                RepositoryResult<Statement> rr = rc.getStatements(null, null, vf.createURI(uri), false);
                                while (rr.hasNext()) {
                                    URISplit us = new URISplit((URI) rr.next().getContext(), base);
                                    entryURIs.add(us.getMetaMetadataURI().toString());
                                }
                            }
                        } catch (RepositoryException e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                rc.close();
                            } catch (RepositoryException e) {
                                log.error(e.getMessage());
                            }
                        }
                        // Check after affected entrys via references in main repository
                        try {
                            rc = rm.rm.getRepository().getConnection();
                            ValueFactory vf = rc.getValueFactory();
                            for (String uri : origURIs) {
                                RepositoryResult<Statement> rr = rc.getStatements(null, null, vf.createURI(uri), false);
                                while (rr.hasNext()) {
                                    URISplit us = new URISplit((URI) rr.next().getContext(), base);
                                    entryURIs.add(us.getMetaMetadataURI().toString());
                                }
                            }
                        } catch (RepositoryException e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                rc.close();
                            } catch (RepositoryException e) {
                                log.error(e.getMessage());
                            }
                        }
                        // Recalculate inferred graphs of all affected entrys
                        for (String uri : entryURIs) {
                            try {
                                Entry entry = rm.rm.getContextManager().getEntry(new java.net.URI(uri));
                                ((InferredMetadataImpl) entry.getInferredMetadata()).update();
                            } catch (URISyntaxException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        log.info("Solr document submitter got interrupted, shutting down submitter thread");
                        return;
                    }
                }
            }
        }
    }

    public ReasoningManagerImpl(RepositoryManagerImpl rm) {
        this.rm = rm;
        Repository mainRepository = rm.getRepository();
        RepositoryConnection rc;
        try {
            rc = mainRepository.getConnection();
            this.detectFactContexts(rc);
            this.rebuildTreeIndex(rc);
            if ("on".equalsIgnoreCase(rm.getConfiguration().getString(
                    Settings.REPOSITORY_REASONING_REBUILD_ON_STARTUP, "off"))) {
                recalculateInferredMetadata();
            }
            this.initListeners();
            this.addListeners();

            this.job = new GraphInferencer(this);
            this.job.start();
        } catch (RepositoryException e) {
            e.printStackTrace();
        }
        this.initializeRepository();
    }

    public void shutdown() {
        if (this.job != null) {
            this.job.interrupt();
        }
        try {
            repository.shutDown();
        } catch (RepositoryException re) {
            log.error("Error when shutting down Sesame reasoning repository: " + re.getMessage());
        }
    }

    Repository getRepository() {
        return this.repository;
    }

    public TreeIndex getTreeIndex() {
        return tii;
    }

    private void initializeRepository() {
        Config config = rm.getConfiguration();
        String storeType = config.getString(Settings.REPOSITORY_REASONING_TYPE, "memory").trim();
        log.info("Reasoning repository type: " + storeType);

        if (storeType.equalsIgnoreCase("memory")) {
            this.repository = new SailRepository(new MemoryStore());
        } else if (storeType.equalsIgnoreCase("native")) {
            if (!config.containsKey(Settings.REPOSITORY_REASONING_PATH)) {
                log.error("Incomplete configuration of provenance repository");
            } else {
                File path = new File(config.getURI(Settings.REPOSITORY_REASONING_PATH));
                String indexes = config.getString(Settings.REPOSITORY_REASONING_INDEXES);

                log.info("Reasoning repository path: " + path);
                log.info("Reasoning repository indexes: " + indexes);

                NativeStore store;
                if (indexes != null) {
                    store = new NativeStore(path, indexes);
                } else {
                    store = new NativeStore(path);
                }

                this.repository = new SailRepository(store);
            }
        }
        try {
            this.repository.initialize();
        } catch (RepositoryException e) {
            log.error(e.getMessage());
        }
    }

    private void detectFactContexts(RepositoryConnection rc) throws RepositoryException {
        RepositoryResult<Statement> rr = rc.getStatements(null,
                RepositoryProperties.reasoningFacts, null, false);
        while(rr.hasNext()) {
            Statement stmt = rr.next();
            contexts.add(new URISplit((URI) stmt.getContext(), rm.getRepositoryURL()).getContextID());
        }
    }

    private void initListeners() {
        this.updateListener = new RepositoryListener() {
            @Override
            public void repositoryUpdated(RepositoryEventObject eventObject) {
                if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
                    entryUpdated((Entry) eventObject.getSource());
                }
            }
        };
        this.removeListener = new RepositoryListener() {
            @Override
            public void repositoryUpdated(RepositoryEventObject eventObject) {
                if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
                    entryRemoved((Entry) eventObject.getSource());
                }
            }
        };
    }

    private void addListeners() {
        rm.registerListener(this.updateListener, RepositoryEvent.MetadataUpdated);
        rm.registerListener(this.updateListener, RepositoryEvent.ExternalMetadataUpdated);
        rm.registerListener(this.removeListener, RepositoryEvent.EntryDeleted);
    }

    private void removeListeners() {
        rm.unregisterListener(this.updateListener, RepositoryEvent.MetadataUpdated);
        rm.unregisterListener(this.updateListener, RepositoryEvent.ExternalMetadataUpdated);
        rm.unregisterListener(this.removeListener, RepositoryEvent.EntryDeleted);
    }

    @Override
    public void rebuildTreeIndex() {
        Repository mainRepository = rm.getRepository();
        RepositoryConnection rc = null;
        try {
            rc = mainRepository.getConnection();
            this.rebuildTreeIndex(rc);
        } catch (RepositoryException e) {
            e.printStackTrace();
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }

    private String getContextId(URI context) {
        return new URISplit(context, rm.getRepositoryURL()).getContextID();
    }
    private void rebuildTreeIndex(RepositoryConnection rc) throws RepositoryException {
        tii = new TreeIndexImpl();
        this.rebuildTreeIndexForProperty(rc, SKOS.BROADER);
        this.rebuildTreeIndexForProperty(rc, SKOS.BROADER_TRANSITIVE);
        this.rebuildTreeIndexForProperty(rc, RDFS.SUBCLASSOF);
        this.rebuildTreeIndexForProperty(rc, RDFS.SUBPROPERTYOF);
    }
    private void rebuildTreeIndexForProperty(RepositoryConnection rc, URI property) throws RepositoryException {
        RepositoryResult<Statement> rr = rc.getStatements(null,
                property, null, false);
        while(rr.hasNext()) {
            Statement stmt = rr.next();
            String to = stmt.getSubject().toString();
            String from = stmt.getObject().toString();
            String contextId = getContextId((URI) stmt.getContext());
            if (contexts.contains(contextId)) {
                tii.initAddTo(from, to, contextId);
            }
        }
        tii.initDone();
    }

    @Override
    public void recalculateKnownInferredMetadata() {
        PrincipalManager pm = rm.getPrincipalManager();
        java.net.URI authUser = pm.getAuthenticatedUserURI();
        if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
            throw new RuntimeException("Only admin or user in admin group can rebuild known inferred graphs");
        }
        RepositoryConnection rc = null;
        try {
            rc = repository.getConnection();
            RepositoryResult<Resource> rr = rc.getContextIDs();
            while(rr.hasNext()) {
                Entry entry = rm.getContextManager().getEntry(java.net.URI.create(rr.next().toString()));
                ((InferredMetadataImpl) entry.getInferredMetadata()).update(rc, true);
            }
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
    public void recalculateInferredMetadata() {
        PrincipalManager pm = rm.getPrincipalManager();
        java.net.URI authUser = pm.getAuthenticatedUserURI();
        if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
            throw new RuntimeException("Only admin or user in admin group can rebuild known inferred graphs");
        }
        RepositoryConnection rc = null;
        try {
            rc = repository.getConnection();
            rc.clear();
            ContextManager cm = rm.getContextManager();
            Set<java.net.URI> contexts = cm.getEntries();

            for (java.net.URI contextURI : contexts) {
                String id = contextURI.toString().substring(contextURI.toString().lastIndexOf("/") + 1);
                Context context = cm.getContext(id);
                if (context != null) {
                    Set<java.net.URI> entries = context.getEntries();
                    for (java.net.URI entryURI : entries) {
                        if (entryURI != null) {
                            Entry entry = cm.getEntry(entryURI);
                            if (entry == null) {
                                continue;
                            }
                            ((InferredMetadataImpl) entry.getInferredMetadata()).update(rc, true);
                        }
                    }
                }
            }
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

    private void entryRemoved(Entry source) {
        InferredMetadataImpl im = (InferredMetadataImpl) source.getInferredMetadata();
        if (im != null) {
            im.remove();
        }

        // If entry is inside "fact enabled" context
        String contextId = source.getContext().getEntry().getId();
        if (contexts.contains(contextId)) {
            removePossibleFactEntry(source);
        }

        // If entry corresponds to an entire context
        // Assumes all individuals therein has already been removed
        // as there is no removal of those here (from repository)
        if (source.getGraphType() == GraphType.Context) {
            tii.removeAllIn(contextId);
        }
    }

    private void removePossibleFactEntry(Entry entry) {
        String contextId = entry.getContext().getEntry().getId();
        addToQueue(tii.remove(entry.getResourceURI().toString(), contextId));
    }


    private void entryUpdated(Entry source) {
        InferredMetadataImpl im = (InferredMetadataImpl) source.getInferredMetadata();
        if (im != null) {
            im.update();
        }

        // If entry is inside "fact enabled" context
        String contextId = source.getContext().getEntry().getId();
        if (contexts.contains(contextId)) {
            updatePossibleFactEntry(source);
        }

        // If entry corresponds to an entire context and it is not already
        // marked as a "fact enabled" context
        if (source.getGraphType() == GraphType.Context) {
            checkContext(source);
        }
    }

    private void updatePossibleFactEntry(Entry entry) {
        String contextId = entry.getContext().getEntry().getId();
        String childURI = entry.getResourceURI().toString();
        String existingParent = tii.parent(childURI);
        String newParent = null;
        for (Statement stmt: entry.getMetadataGraph()) {
            if (isTreePredicate(stmt)) {
                newParent = stmt.getObject().toString();
            }
        }
        if (existingParent != null && !existingParent.equals(newParent)) {
            addToQueue(tii.removeFrom(childURI, existingParent, contextId));
        }
        if (newParent != null && !newParent.equals(existingParent)) {
            addToQueue(tii.addTo(childURI, newParent, contextId));
        }
    }

    private boolean isTreePredicate(Statement stmt) {
        URI predicate = stmt.getPredicate();
        return predicate.equals(SKOS.BROADER)
                || predicate.equals(SKOS.BROADER_TRANSITIVE)
                || predicate.equals(RDFS.SUBCLASSOF)
                || predicate.equals(RDFS.SUBPROPERTYOF);
    }

    private void checkContext(Entry entry) {
        Repository mainRepository = rm.getRepository();
        RepositoryConnection rc = null;
        try {
            rc = mainRepository.getConnection();
            String contextId = entry.getId();
        RepositoryResult<Statement> rr = rc.getStatements(((EntryImpl) entry).getSesameResourceURI(),
                RepositoryProperties.reasoningFacts, null, false);
            if (rr.hasNext()) {
                if (!contexts.contains(contextId)) {
                    // Context is just made into fact context
                    Context context = (Context) entry.getResource();
                    TreeIndexContext tic = tii.addContext(contextId);
                    for (java.net.URI entryURI: context.getEntries()) {
                        for (Statement stmt: context.getByEntryURI(entryURI).getMetadataGraph()) {
                            if (isTreePredicate(stmt)) {
                                tic.initAddTo(stmt.getSubject().toString(), stmt.getObject().toString());
                            }
                        }
                    }
                    addToQueue(tic.initDone());
                }
            } else {
                if (contexts.contains(contextId)) {
                    // Fact context is just removed and not a fact context anymore.
                    addToQueue(tii.removeAllIn(contextId));
                }
            }
        } catch (RepositoryException e) {
            e.printStackTrace();
        } finally {
            try {
                rc.close();
            } catch (RepositoryException e) {
                log.error(e.getMessage());
            }
        }
    }
    private void addToQueue(TreeChange tc) {
        synchronized (changeQueue) {
            this.changeQueue.add(tc);
        }
    }
}