package org.entrystore.impl.reasoning;

import org.entrystore.reasoning.ChangeType;
import org.entrystore.reasoning.TreeChange;

import java.util.*;

/**
 * Created by matthias on 2018-01-07.
 */
class TreeIndexContext{
    private HashMap<String,String> toParent = new HashMap<>();
    private HashSet<String> top = new HashSet<>();
    private boolean enabled = false;


    void initAddTo(String child, String parent) {
        this.toParent.put(child, parent);
    }
    TreeChange initDone() {
        this.enabled = true;
        top = new HashSet<>();
        Set<String> children = this.toParent.keySet();
        for (String parent : this.toParent.keySet()) {
            if (!children.contains(parent)) {
                top.add(parent);
            }
        }
        HashSet<String> nodes = new HashSet<String>(this.toParent.keySet());
        nodes.addAll(this.top);
        return new TreeChangeImpl(ChangeType.AddAll, nodes);
    }

    TreeChange removeAll() {
        return new TreeChangeImpl(ChangeType.RemoveAll, this.top);
    }

    TreeChange addTo(String child, String parent) {
        this.toParent.put(child, parent);
        if (this.top.contains(child)) {
            this.top.remove(child);
        }
        if (!this.toParent.containsKey(parent)) {
            this.top.add(parent);
        }
        //TODO handle loops, multiple parents etc.
        if (this.enabled) {
            return new TreeChangeImpl(ChangeType.AddTo, child);
        } else {
            return null;
        }
    }

    TreeChange remove(String node) {
        this.top.remove(node);
        this.toParent.remove(node);
        ChangeType ct = ChangeType.Remove;

        //Handle weird case (not supposed to remove non-leaves (top are a kind of leaves))
        if (this.toParent.values().contains(node)) {
            for (Map.Entry<String, String> entry : this.toParent.entrySet()) {
                if(entry.getValue().equals(node)){
                    this.toParent.remove(entry.getKey());
                    this.top.add(entry.getKey());
                }
            }
            // Hack, to indicate that a more serious update is needed.
            ct = ChangeType.RemoveAll;
        }
        if (this.enabled) {
            return new TreeChangeImpl(ct, node);
        } else {
            return null;
        }
    }

    TreeChange removeFrom(String child, String parent) {
        this.toParent.remove(child);
        this.top.add(child);
        if (this.enabled) {
            return new TreeChangeImpl(ChangeType.RemoveFrom, child);
        } else {
            return null;
        }
    }

    boolean inTree(String node) {
        return this.top.contains(node) || this.toParent.keySet().contains(node);
    }

    Set<String> ancestors(String node) {
        HashSet<String> ancestors = new HashSet<String>();
        String parent = this.toParent.get(node);
        while(parent != null && !ancestors.contains(parent)) {
            ancestors.add(parent);
            parent = this.toParent.get(parent);
        }
        return ancestors;
    }

    String parent(String node) {
        return this.toParent.get(node);
    }
}