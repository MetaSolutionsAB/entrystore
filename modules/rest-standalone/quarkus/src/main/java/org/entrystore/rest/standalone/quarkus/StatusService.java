package org.entrystore.rest.standalone.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.entrystore.rest.standalone.quarkus.configuration.EntryStoreConfiguration;
import org.entrystore.rest.standalone.quarkus.model.api.StatusResponse;

@ApplicationScoped
public class StatusService {

	@Inject
	EntryStoreConfiguration configuration;

	public StatusResponse getStatus() {
		return new StatusResponse(
			configuration.app().version(),
			"online");
	}
}
