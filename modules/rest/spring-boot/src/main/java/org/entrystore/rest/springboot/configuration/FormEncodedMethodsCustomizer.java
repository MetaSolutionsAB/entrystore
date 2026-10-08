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

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Makes Jetty parse form-urlencoded bodies into request parameters for POST only, as the Servlet specification
 * and 5.x do, instead of for POST and PUT. Otherwise the first {@code getParameter()} call, made by filters
 * before any access check, reads a form-urlencoded PUT body into memory, and a raw PUT of a file resource with
 * that content type would be stored empty. {@code spring.mvc.formcontent.filter.enabled=false} keeps Spring's
 * {@code FormContentFilter} from doing the same for PUT, PATCH and DELETE.
 */
@Component
public class FormEncodedMethodsCustomizer implements WebServerFactoryCustomizer<JettyServletWebServerFactory> {

	@Override
	public void customize(JettyServletWebServerFactory factory) {
		factory.addServerCustomizers(FormEncodedMethodsCustomizer::parseFormsOfPostOnly);
	}

	static void parseFormsOfPostOnly(Server server) {
		for (Connector connector : server.getConnectors()) {
			for (var connectionFactory : connector.getConnectionFactories()) {
				if (connectionFactory instanceof HttpConfiguration.ConnectionFactory httpConnectionFactory) {
					httpConnectionFactory.getHttpConfiguration().setFormEncodedMethods(HttpMethod.POST.asString());
				}
			}
		}
	}
}
