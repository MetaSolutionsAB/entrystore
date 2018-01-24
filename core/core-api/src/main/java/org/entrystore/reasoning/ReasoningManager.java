package org.entrystore.reasoning;

import org.entrystore.Entry;

/**
 * Created by matthias on 2018-01-07.
 */
public interface ReasoningManager {
    TreeIndex getTreeIndex();
    void rebuildTreeIndex();
    void recalculateInferredMetadata();
    void recalculateKnownInferredMetadata();
}
