package org.entrystore.rest.standalone.quarkus.resource;

import jakarta.ws.rs.core.Response;
import org.entrystore.rest.standalone.quarkus.model.api.ErrorResponse;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.time.LocalDateTime;

public class ExceptionMapper {

	@ServerExceptionMapper
	public RestResponse<ErrorResponse> mapException(IllegalArgumentException ex) {

		// Build the response body
		ErrorResponse responseBody = new ErrorResponse(
			LocalDateTime.now(),
			Response.Status.BAD_REQUEST.getStatusCode(),
			"",
			ex.getMessage());

		return RestResponse.status(Response.Status.BAD_REQUEST, responseBody);
	}
}
