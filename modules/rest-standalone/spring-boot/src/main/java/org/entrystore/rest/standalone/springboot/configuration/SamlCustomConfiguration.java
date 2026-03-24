package org.entrystore.rest.standalone.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "entrystore.auth.saml")
public record SamlCustomConfiguration(
		@DefaultValue("false") boolean enabled,
		String defaultIdp,
		RedirectSuccess redirectSuccess,
		RedirectFailure redirectFailure
) {
	public SamlCustomConfiguration {
		if (redirectSuccess == null) redirectSuccess = new RedirectSuccess("/auth/user");
		if (redirectFailure == null) redirectFailure = new RedirectFailure("/auth/user");
	}

	public record RedirectSuccess(String url) {}
	public record RedirectFailure(String url) {}
}
