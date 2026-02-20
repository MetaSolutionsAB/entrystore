package org.entrystore.rest.standalone.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "entrystore.auth.saml")
public record SamlCustomConfiguration(
		@DefaultValue("false") boolean enabled,
		String defaultIdp,
		@DefaultValue("/start") String redirectSuccess,
		@DefaultValue("/signin") String redirectFailure
) {
}
