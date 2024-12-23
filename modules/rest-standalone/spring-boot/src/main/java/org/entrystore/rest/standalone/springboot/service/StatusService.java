package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.rest.standalone.springboot.EntryStoreConfiguration;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final EntryStoreConfiguration configuration;

	public StatusResponse getStatus() {
		return new StatusResponse(
			configuration.app().version(),
			"online");
	}
}
