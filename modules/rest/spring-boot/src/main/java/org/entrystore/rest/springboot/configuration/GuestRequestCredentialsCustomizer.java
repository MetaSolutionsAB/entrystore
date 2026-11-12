/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.configuration;

import org.entrystore.rest.springboot.filter.IgnoreAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Puts {@link GuestRequestCredentialsHandler} in front of the servlet context, where a request has not yet been
 * given its session.
 */
@Component
public class GuestRequestCredentialsCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {

	private final IgnoreAuthFilter ignoreAuthFilter;
	private final String sessionCookieName;

	public GuestRequestCredentialsCustomizer(IgnoreAuthFilter ignoreAuthFilter,
			@Value("${server.servlet.session.cookie.name:auth_token}") String sessionCookieName) {
		this.ignoreAuthFilter = ignoreAuthFilter;
		this.sessionCookieName = sessionCookieName;
	}

	@Override
	public void customize(JettyServletWebServerFactory factory) {
		factory.addServerCustomizers(server ->
				server.insertHandler(new GuestRequestCredentialsHandler(ignoreAuthFilter, sessionCookieName)));
	}
}
