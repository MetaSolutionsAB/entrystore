package org.entrystore.rest.standalone.quarkus.model.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.entrystore.rest.standalone.quarkus.model.EntryType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@XmlRootElement
public class EntryCreateRequest {

	@NotEmpty(message = "Field 'entryId' must not be empty")
	private String entryId;

	@NotNull(message = "Field 'type' must not be not null")
	private EntryType type;
}
