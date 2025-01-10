package org.entrystore.rest.standalone.quarkus.model.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.entrystore.rest.standalone.quarkus.model.EntryType;

@Getter
@AllArgsConstructor
public class EntryCreateRequest {

	private String entryId;

	private EntryType type;
}
