package org.entrystore.rest.standalone.quarkus.model.api;

import org.entrystore.rest.standalone.quarkus.model.EntryType;

public record EntryResponse(String entryId, EntryType type) {
}
