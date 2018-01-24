package org.entrystore.impl.reasoning;

import org.entrystore.reasoning.TreeChange;
import org.entrystore.reasoning.TreeIndex;

import java.util.HashMap;
import java.util.Set;

/**
 * Created by matthias on 2018-01-07.
 */
public class TreeIndexImpl implements TreeIndex {

    private HashMap<String, TreeIndexContext> indexes = new HashMap<String, TreeIndexContext>();

    TreeIndexContext addContext(String contextId) {
        return indexes.computeIfAbsent(contextId, k -> new TreeIndexContext());
    }

    void initAddTo(String child, String parent, String contextId) {
        TreeIndexContext tic = indexes.computeIfAbsent(contextId, k -> new TreeIndexContext());
        tic.initAddTo(child, parent);
    }

    void initDone() {
        for (TreeIndexContext tic: indexes.values()) {
            tic.initDone();
        }
    }

    @Override
    public TreeChange addTo(String child, String parent, String contextId) {
        TreeIndexContext tic = indexes.get(contextId);
        if (tic != null) {
            return tic.addTo(child, parent);
        }
        return null;
    }

    @Override
    public TreeChange remove(String node, String contextId) {
        TreeIndexContext tic = indexes.get(contextId);
        if (tic != null) {
            return tic.remove(node);
        }
        return null;
    }

    @Override
    public TreeChange removeFrom(String child, String parent, String contextId) {
        TreeIndexContext tic = indexes.get(contextId);
        if (tic != null) {
            return tic.removeFrom(child, parent);
        }
        return null;
    }

    @Override
    public TreeChange removeAllIn(String contextId) {
        TreeIndexContext tic = indexes.get(contextId);
        indexes.remove(contextId);
        return tic.removeAll();
    }

    @Override
    public boolean inTree(String node) {
        TreeIndexContext tic = this.getTree(node);
        return tic != null;
    }

    TreeIndexContext getTree(String node) {
        for (TreeIndexContext tic : indexes.values()) {
            if (tic.inTree(node)) {
                return tic;
            }
        }
        return null;
    }

    @Override
    public String parent(String node) {
        TreeIndexContext tic = this.getTree(node);
        if (tic != null) {
            return tic.parent(node);
        }
        return null;
    }

    @Override
    public Set<String> ancestors(String node) {
        TreeIndexContext tic = this.getTree(node);
        if (tic != null) {
            return tic.ancestors(node);
        }
        return null;
    }
}