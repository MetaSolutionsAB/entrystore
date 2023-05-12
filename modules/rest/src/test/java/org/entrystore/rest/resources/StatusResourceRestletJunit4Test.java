
package org.entrystore.rest.resources;

import java.io.IOException;
import java.util.List;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.MediaType;
import org.restlet.engine.Engine;
import org.restlet.engine.connector.HttpProtocolHelper;
import org.restlet.representation.ObjectRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.Finder;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.restlet.test.RestletTestCase;

/**
 * Test the annotated resources, client and server sides.
 *
 * @author Jerome Louvel
 */
@RunWith(MockitoJUnitRunner.class)
public class StatusResourceRestletJunit4Test extends RestletTestCase {

	private ClientResource clientResource;
	private StatusResourceInterface statusResource;
	private Response response;
	@Mock Context restletContext;

	@BeforeEach
	void beforeEach() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_COOKIE_PATH, "/");
		config.setProperty(Settings.SMTP_EMAIL_FROM, "info@meta.se");
		config.setProperty(Settings.SMTP_HOST, "localhost");

		Request request = new Request();
		request.setResourceRef("");
		this.response = new Response(request);

//		lenient().when(statusResource.getPM()).thenReturn(pm);
//		lenient().when(statusResource.getRM()).thenReturn(rm);
//		lenient().when(rm.getConfiguration()).thenReturn(config);
//		lenient().when(statusResource.getResponse()).thenReturn(response);
//		lenient().when(statusResource.getRequest()).thenReturn(request);
//		lenient().doCallRealMethod().when(statusResource).unauthorizedPOST();
//		doCallRealMethod().when(statusResource).sendMessage(any(JsonRepresentation.class));
	}


	protected void setUp() throws Exception {
		super.setUp();
		Engine.getInstance().getRegisteredConverters().clear();
		Engine.getInstance().setRegisteredProtocols(List.of(new HttpProtocolHelper()));
//		Engine.getInstance().getRegisteredConverters().add(new JacksonConverter());
		Engine.getInstance().registerDefaultConverters();
	}

	@Override
	protected void tearDown() throws Exception {
		clientResource = null;
		statusResource = null;
		super.tearDown();
	}

	@Test
	@Ignore
	public void testGet() throws IOException, ResourceException {
		Finder finder = new Finder(restletContext, StatusResource.class);
		finder.setTargetClass(StatusResource.class);
		this.clientResource = new ClientResource("http://local");
		this.clientResource.setNext(finder);
		StatusResourceInterface statusResource = clientResource.wrap(StatusResourceInterface.class);
		Representation representation = statusResource.represent();
		assertNotNull(representation);



		String result = clientResource.get(MediaType.TEXT_XML).getText();
		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);

		result = clientResource.get(MediaType.APPLICATION_XML).getText();
		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);

		result = clientResource.get(MediaType.APPLICATION_ALL_XML).getText();
		assertEquals("<MyBean><description>myDescription</description><name>myName</name></MyBean>", result);

		result = clientResource.get(MediaType.APPLICATION_JSON).getText();
		assertEquals("{\"description\":\"myDescription\",\"name\":\"myName\"}", result);

		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = true;
		result = clientResource.get(MediaType.APPLICATION_JAVA_OBJECT_XML).getText();
		assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") && result.contains("<java version=\""));
		ObjectRepresentation.VARIANT_OBJECT_XML_SUPPORTED = false;
	}

	public interface StatusResourceInterface {
		@Get
		Representation represent() throws ResourceException;
	}
}
