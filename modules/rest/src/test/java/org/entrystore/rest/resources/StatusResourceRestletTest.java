
package org.entrystore.rest.resources;

import java.io.IOException;
import org.restlet.data.MediaType;
import org.restlet.engine.Engine;
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
public class StatusResourceRestletTest extends RestletTestCase {

	private ClientResource clientResource;

	private StatusResourceInterface statusResource;

	protected void setUp() throws Exception {
		super.setUp();
		Engine.getInstance().getRegisteredConverters().clear();
//		Engine.getInstance().getRegisteredConverters().add(new JacksonConverter());
		Engine.getInstance().registerDefaultConverters();
		Finder finder = new Finder();
		finder.setTargetClass(StatusResource.class);

		this.clientResource = new ClientResource("http://local");
		this.clientResource.setNext(finder);
		this.statusResource = clientResource.wrap(StatusResourceInterface.class);
	}

	@Override
	protected void tearDown() throws Exception {
		clientResource = null;
		statusResource = null;
		super.tearDown();
	}

	public void testGet() throws IOException, ResourceException {
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
