package org.entrystore.rest.standalone.springboot.service;

import org.entrystore.rest.standalone.springboot.model.EntryType;
import org.entrystore.rest.standalone.springboot.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.springboot.model.api.EntryResponse;
import org.springframework.stereotype.Service;

@Service
public class EntryService {

	public EntryResponse getEntry(int entryId) {
		if (entryId < 1) {
			throw new IllegalArgumentException("What is this entry ID: '" + entryId + "'?");
		}
		return new EntryResponse(String.valueOf(entryId), EntryType.LOCAL);
	}

	public EntryResponse createEntry(EntryCreateRequest entryRequest) {
		// store the entry

		// return stored entry data
		return new EntryResponse(entryRequest.getEntryId(), entryRequest.getType());
	}
}
