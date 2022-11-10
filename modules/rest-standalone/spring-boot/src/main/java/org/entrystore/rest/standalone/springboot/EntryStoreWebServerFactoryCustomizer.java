package org.entrystore.rest.standalone.springboot;

import org.springframework.boot.web.embedded.jetty.JettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

public class EntryStoreWebServerFactoryCustomizer implements WebServerFactoryCustomizer {

	@Override
	public void customize(WebServerFactory factory) {
		JettyReactiveWebServerFactory jetty = (JettyReactiveWebServerFactory) factory;
		jetty.addServerCustomizers();
	}
}
