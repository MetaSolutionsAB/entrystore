package org.entrystore.rest.standalone.quarkus.model.api;

import java.time.LocalDateTime;

public record ErrorResponse(LocalDateTime timestamp, int status, String path, String errors) {
}
