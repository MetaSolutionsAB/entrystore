package org.entrystore.rest.standalone.javalin.model.api;

import org.entrystore.rest.standalone.javalin.model.EntryType;

public record EntryResponse(String entryId, EntryType type) {
}
