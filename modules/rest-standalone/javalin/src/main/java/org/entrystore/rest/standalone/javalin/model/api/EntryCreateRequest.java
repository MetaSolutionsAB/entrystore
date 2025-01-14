package org.entrystore.rest.standalone.javalin.model.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.entrystore.rest.standalone.javalin.model.EntryType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryCreateRequest {

	private String entryId;

	private EntryType type;
}
