package org.entrystore.rest.standalone.quarkus.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.entrystore.rest.standalone.quarkus.model.EntryType;
import org.entrystore.rest.standalone.quarkus.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.quarkus.model.api.EntryResponse;

@ApplicationScoped
public class EntryService {

	public EntryResponse getEntry(int entryId) {
		if (entryId < 1) {
			throw new IllegalArgumentException("What is this entry ID: '" + entryId + "'?");
		}
		return EntryResponse.builder()
			.entryId(String.valueOf(entryId))
			.type(EntryType.LOCAL)
			.build();
	}

	public EntryResponse createEntry(EntryCreateRequest entryRequest) {
		// store the entry

		// return stored entry data
		return EntryResponse.builder()
			.entryId(entryRequest.getEntryId())
			.type(entryRequest.getType())
			.build();
	}
}
