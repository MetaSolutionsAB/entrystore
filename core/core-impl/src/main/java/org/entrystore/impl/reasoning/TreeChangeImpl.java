package org.entrystore.impl.reasoning;

import org.entrystore.reasoning.ChangeType;
import org.entrystore.reasoning.TreeChange;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by matthias on 2018-01-07.
 */
public class TreeChangeImpl implements TreeChange {

    private final ChangeType type;
    private final Set<String> nodes;

    TreeChangeImpl(ChangeType type, String node) {
        this.type = type;
        this.nodes = new HashSet<String>();
        this.nodes.add(node);
    }

    TreeChangeImpl(ChangeType type, Set<String> nodes) {
        this.type = type;
        this.nodes = nodes;
    }

    @Override
    public ChangeType getType() {
        return this.type;
    }

    @Override
    public Set<String> getNodes() {
        return this.nodes;
    }
}
