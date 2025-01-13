package org.entrystore.rest.standalone.quarkus.model.api;

import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Value;
import org.entrystore.rest.standalone.quarkus.model.EntryType;

@XmlRootElement
@Value
@Builder
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class EntryResponse {
	String entryId;
	EntryType type;
}
