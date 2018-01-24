package org.entrystore.reasoning;

import java.net.URI;
import java.util.Set;

/**
 * Created by matthias on 2018-01-06.
 */
public interface TreeIndex {
    TreeChange addTo(String child, String parent, String contextId);
    TreeChange remove(String node, String contextId);
    TreeChange removeFrom(String child, String parent, String contextId);
    TreeChange removeAllIn(String contextId);

    boolean inTree(String node);
    Set<String> ancestors(String node);
    String parent(String node);
}