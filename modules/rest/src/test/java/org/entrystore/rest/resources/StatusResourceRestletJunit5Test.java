
package org.entrystore.rest.resources;

import java.io.IOException;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.RestletTestJunit5;
import org.entrystore.rest.TestEntryStoreServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Response;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class StatusResourceRestletJunit5Test extends RestletTestJunit5 {

	private final Logger log = LoggerFactory.getLogger(StatusResourceRestletJunit5Test.class);

	private ClientResource clientResource;
	private StatusResourceInterface statusResource;
	private Response response;
	private Component component;
	@Mock Context restletContext;

	@BeforeEach
	public void beforeEach() throws Exception {
		Config config = new PropertiesConfiguration("Test EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_COOKIE_PATH, "/");
		config.setProperty(Settings.SMTP_EMAIL_FROM, "info@meta.se");
		config.setProperty(Settings.SMTP_HOST, "localhost");

		TestEntryStoreServer server = new TestEntryStoreServer();
		server.start();

//		Request request = new Request();
//		request.setResourceRef("");
//		this.response = new Response(request);

//		lenient().when(statusResource.getPM()).thenReturn(pm);
//		lenient().when(statusResource.getRM()).thenReturn(rm);
//		lenient().when(rm.getConfiguration()).thenReturn(config);
//		lenient().when(statusResource.getResponse()).thenReturn(response);
//		lenient().when(statusResource.getRequest()).thenReturn(request);
//		lenient().doCallRealMethod().when(statusResource).unauthorizedPOST();
//		doCallRealMethod().when(statusResource).sendMessage(any(JsonRepresentation.class));
	}

//	@Override
//	protected void tearDown() throws Exception {
//		clientResource = null;
//		statusResource = null;
//		super.tearDown();
//	}

	@Test
	@Disabled("To make compile go through")
	public void test_get() throws IOException, ResourceException {
		log.info("Port: " + getPort());

//		Finder finder = new Finder(restletContext, StatusResource.class);
//		finder.setTargetClass(StatusResource.class);
//		this.clientResource = new ClientResource("http://local");
//		this.clientResource.setNext(finder);
//		StatusResourceInterface statusResource = clientResource.wrap(StatusResourceInterface.class);
//		Representation representation = statusResource.represent();
//		assertNotNull(representation);
//
//		String result = clientResource.get(MediaType.TEXT_XML).getText();
//		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);
//
//		result = clientResource.get(MediaType.APPLICATION_XML).getText();
//		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);
//
//		result = clientResource.get(MediaType.APPLICATION_ALL_XML).getText();
//		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);
//
//		result = clientResource.get(MediaType.APPLICATION_JSON).getText();
//		assertEquals("{\"description\":\"myDescription\",\"name\":\"myName\"}", result);
//
//		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = true;
//		result = clientResource.get(MediaType.APPLICATION_JAVA_OBJECT_XML).getText();
//		assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") && result.contains("<java version=\""));
//		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = false;
	}

	public interface StatusResourceInterface {
		@Get
		Representation represent() throws ResourceException;
	}
}
