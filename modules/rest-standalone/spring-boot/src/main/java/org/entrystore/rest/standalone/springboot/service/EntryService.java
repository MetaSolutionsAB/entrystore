package org.entrystore.rest.standalone.springboot.service;

import org.entrystore.rest.standalone.springboot.model.EntryType;
import org.entrystore.rest.standalone.springboot.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.springboot.model.api.EntryResponse;
import org.springframework.stereotype.Service;

@Service
public class EntryService {

	public EntryResponse getEntry(int entryId) {
		return new EntryResponse(String.valueOf(entryId), EntryType.LOCAL);
	}

	public EntryResponse createEntry(EntryCreateRequest newEntry) {
		// store the entry

		// return stored entry data
		return new EntryResponse(newEntry.getEntryId(), newEntry.getType());
	}
}
