package org.entrystore.rest.standalone.quarkus;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.entrystore.rest.standalone.quarkus.model.api.StatusResponse;

@Path("/management/status")
public class StatusResource {

	@Inject
	StatusService service;

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public StatusResponse getStatus() {
		return service.getStatus();
	}

/*	@GET
	@Produces(MediaType.TEXT_PLAIN)
	@Path("/greeting/{name}")
	public String greeting(String name) {
		return service.greeting(name);
	}
*/
}
