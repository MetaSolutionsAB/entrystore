package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.model.api.EntryResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntryService {

	public EntryResponse getEntry(int entryId) {
		return new EntryResponse(String.valueOf(entryId), "a type");
	}
}
