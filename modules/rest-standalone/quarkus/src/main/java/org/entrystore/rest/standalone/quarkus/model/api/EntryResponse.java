package org.entrystore.rest.standalone.quarkus.model.api;

import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.entrystore.rest.standalone.quarkus.model.EntryType;

@XmlRootElement
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryResponse {
	String entryId;
	EntryType type;
}
