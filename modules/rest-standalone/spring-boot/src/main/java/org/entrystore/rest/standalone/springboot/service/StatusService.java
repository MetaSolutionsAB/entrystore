package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.rest.standalone.springboot.configuration.InfoAppPropertiesConfiguration;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final InfoAppPropertiesConfiguration configuration;

	private final RepositoryManager repositoryManager;

	public StatusResponse getStatus() {
		return new StatusResponse(
			configuration.app().version(),
			repositoryManager != null ? "online" : "offline");
	}
}
