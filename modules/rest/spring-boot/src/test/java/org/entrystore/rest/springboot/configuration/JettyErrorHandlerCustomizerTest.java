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

import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.entrystore.rest.springboot.configuration.JettyErrorHandlerCustomizer.SanitizedErrorWriter;
import org.entrystore.rest.springboot.configuration.JettyErrorHandlerCustomizer.SanitizedServletErrorHandler;
import org.entrystore.rest.springboot.configuration.JettyErrorHandlerCustomizer.SanitizedServerErrorHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.jetty.JettyServerCustomizer;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JettyErrorHandlerCustomizerTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	// The raw connector-level URL as seen behind a reverse proxy — the internal host and port
	// must never appear in the error body (pentest ref 3.1.5, ENTRYSTORE-1098).
	private static final String INTERNAL_URL = "http://localhost:8501/store/search?type=solr&query=*:*";

	private Request requestWithUri(String uri) {
		Request request = mock(Request.class);
		when(request.getHttpURI()).thenReturn(HttpURI.from(uri));
		return request;
	}

	@Test
	void writeJson_emitsOnlySanitizedEnvelopeFields() {
		StringWriter out = new StringWriter();

		SanitizedErrorWriter.writeJson(requestWithUri(INTERNAL_URL), new PrintWriter(out), 400);

		Map<?, ?> json = JSON.readValue(out.toString(), Map.class);
		assertNotNull(json.get("timestamp"));
		assertEquals(400, json.get("status"));
		assertEquals("/store/search", json.get("path"));
		assertEquals("Bad Request", json.get("error"));
		assertEquals(4, json.size());
		assertFalse(out.toString().contains("localhost"));
		assertFalse(out.toString().contains("8501"));
		assertFalse(out.toString().contains("query"));
	}

	@Test
	void writeJson_withoutUsableUri_omitsPathAndStaysValidJson() {
		Request request = mock(Request.class);
		when(request.getHttpURI()).thenThrow(new IllegalStateException("request rejected during parsing"));
		StringWriter out = new StringWriter();

		SanitizedErrorWriter.writeJson(request, new PrintWriter(out), 400);

		Map<?, ?> json = JSON.readValue(out.toString(), Map.class);
		assertNull(json.get("path"));
		assertEquals(400, json.get("status"));
		assertEquals("Bad Request", json.get("error"));
	}

	@Test
	void writePlain_containsStatusAndPathButNoAbsoluteUrl() {
		StringWriter out = new StringWriter();
		PrintWriter writer = new PrintWriter(out);

		SanitizedErrorWriter.writePlain(requestWithUri(INTERNAL_URL), writer, 400);
		writer.flush();

		String body = out.toString();
		assertTrue(body.contains("HTTP ERROR 400 Bad Request"));
		assertTrue(body.contains("PATH: /store/search"));
		assertFalse(body.contains("http://"));
		assertFalse(body.contains("localhost"));
	}

	@Test
	void writeHtml_containsStatusButNoUriMessageOrOrigin() throws Exception {
		StringWriter out = new StringWriter();

		SanitizedErrorWriter.writeHtml(out, 400);

		String body = out.toString();
		assertTrue(body.contains("HTTP ERROR 400 Bad Request"));
		assertFalse(body.contains("http://"));
		assertFalse(body.contains("URI"));
		assertFalse(body.contains("MESSAGE"));
		assertFalse(body.contains("ORIGIN"));
	}

	@Test
	void writeJson_escapesClientControlledPath() {
		StringWriter out = new StringWriter();

		SanitizedErrorWriter.writeJson(requestWithUri("http://localhost:8501/store/%22%3Cscript%3E"), new PrintWriter(out), 404);

		Map<?, ?> json = JSON.readValue(out.toString(), Map.class);
		assertFalse(json.get("path").toString().contains("<script>"));
	}

	@Test
	void servletErrorHandler_writesErrorBodiesForAllHttpMethods() {
		// Matches Spring Boot's JettyEmbeddedErrorHandler, which widens the core handler's
		// GET/POST/HEAD default so DELETE/PUT errors also get a body.
		SanitizedServletErrorHandler handler = new SanitizedServletErrorHandler();

		assertTrue(handler.errorPageForMethod("DELETE"));
		assertTrue(handler.errorPageForMethod("PUT"));
		assertTrue(handler.errorPageForMethod("GET"));
	}

	@Test
	void customize_installsSanitizedHandlersOnContextAndServer() throws Exception {
		JettyServletWebServerFactory factory = mock(JettyServletWebServerFactory.class);

		new JettyErrorHandlerCustomizer().customize(factory);

		var configurationCaptor = ArgumentCaptor.forClass(org.eclipse.jetty.ee11.webapp.Configuration.class);
		verify(factory).addConfigurations(configurationCaptor.capture());
		WebAppContext context = new WebAppContext();
		configurationCaptor.getValue().configure(context);
		assertInstanceOf(SanitizedServletErrorHandler.class, context.getErrorHandler());

		var serverCustomizerCaptor = ArgumentCaptor.forClass(JettyServerCustomizer.class);
		verify(factory).addServerCustomizers(serverCustomizerCaptor.capture());
		Server server = new Server();
		serverCustomizerCaptor.getValue().customize(server);
		assertInstanceOf(SanitizedServerErrorHandler.class, server.getErrorHandler());
	}
}
