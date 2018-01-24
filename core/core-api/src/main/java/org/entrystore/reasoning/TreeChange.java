package org.entrystore.reasoning;

import org.entrystore.Entry;

import java.util.Set;

/**
 * Created by matthias on 2018-01-06.
 */
public interface TreeChange {
    ChangeType getType();
    Set<String> getNodes();
}
