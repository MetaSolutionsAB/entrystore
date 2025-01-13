package org.entrystore.rest.standalone.quarkus.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.entrystore.rest.standalone.quarkus.model.api.StatusResponse;
import org.entrystore.rest.standalone.quarkus.service.StatusService;

@Path("/management/status")
public class StatusResource {

	@Inject
	StatusService service;

	@GET
	@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
	public StatusResponse getStatus() {
		return service.getStatus();
	}
}
