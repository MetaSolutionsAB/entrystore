package org.entrystore.rest.standalone.springboot.model.api;

import org.entrystore.rest.standalone.springboot.model.EntryType;

public record EntryResponse(String entryId, EntryType type) {
}
