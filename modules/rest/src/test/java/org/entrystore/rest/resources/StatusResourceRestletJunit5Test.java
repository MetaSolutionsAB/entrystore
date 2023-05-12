
package org.entrystore.rest.resources;

import java.io.IOException;
import java.util.Map;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.RestletTestJunit5;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.Protocol;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.Finder;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;

@ExtendWith(MockitoExtension.class)
public class StatusResourceRestletJunit5Test extends RestletTestJunit5 {

	private ClientResource clientResource;
	private StatusResourceInterface statusResource;
	private Response response;
	private Component component;
	@Mock Context restletContext;

//	@BeforeEach
	public void setUp() throws Exception {
		Context context = new Context();
		Map<String, Object> attributes = Map.of(
				"", ""
		);
		context.setAttributes(attributes);

		this.component = new Component();
		this.component.getDefaultHost().attach("/", new EntryStoreApplication(context));
		this.component.getServers().add(Protocol.HTTP, getPort());
		this.component.start();
	}


//	@BeforeEach
	public void beforeEach() {
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

//	@Override
//	protected void tearDown() throws Exception {
//		clientResource = null;
//		statusResource = null;
//		super.tearDown();
//	}

	@Test
	@Disabled
	public void testGet() throws IOException, ResourceException {
		Finder finder = new Finder(restletContext, StatusResource.class);
		finder.setTargetClass(StatusResource.class);
		this.clientResource = new ClientResource("http://local");
		this.clientResource.setNext(finder);
		StatusResourceInterface statusResource = clientResource.wrap(StatusResourceInterface.class);
		Representation representation = statusResource.represent();
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
