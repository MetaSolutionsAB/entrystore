package org.entrystore.rest.standalone.quarkus.resource;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.entrystore.rest.standalone.quarkus.model.api.EntryCreateRequest;
import org.entrystore.rest.standalone.quarkus.model.api.EntryResponse;
import org.entrystore.rest.standalone.quarkus.service.EntryService;

@Path("/entries")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class EntryResource {

	@Inject
	EntryService service;

	@GET
	@Path("/{entryId}")
	public EntryResponse getEntry(int entryId) {
		return service.getEntry(entryId);
	}

	@POST
	public EntryResponse createEntry(@Valid EntryCreateRequest entryRequest) {
		return service.createEntry(entryRequest);
	}

}
