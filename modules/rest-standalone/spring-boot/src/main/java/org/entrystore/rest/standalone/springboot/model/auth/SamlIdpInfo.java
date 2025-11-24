package org.entrystore.rest.standalone.springboot.model.auth;

import lombok.Builder;

import java.util.List;

@Builder
public record SamlIdpInfo(String id, List<String> domains, boolean autoProvisioning) {
}
