package org.entrystore.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.restlet.data.Status.SUCCESS_OK;

import java.io.IOException;
import javax.mail.MessagingException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.impl.PrincipalManagerImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.ext.json.JsonRepresentation;

@ExtendWith(MockitoExtension.class)
class StatusResourceTest {

	@Mock EntryStoreApplication app;
	@Mock RepositoryManagerImpl rm;
	@Mock PrincipalManagerImpl pm;
	@Mock	Context context;
	@Mock Entry entry;
	@Mock StatusResource statusResource;
	Response response;

	@BeforeEach
	void beforeEach() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		LoginTokenCache loginTokenCache = new LoginTokenCache(config);
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

		lenient().when(statusResource.getPM()).thenReturn(pm);
		lenient().when(statusResource.getRM()).thenReturn(rm);
		lenient().when(rm.getConfiguration()).thenReturn(config);
		lenient().when(statusResource.getResponse()).thenReturn(response);
		lenient().when(statusResource.getRequest()).thenReturn(request);
		lenient().doCallRealMethod().when(statusResource).unauthorizedPOST();
//		doCallRealMethod().when(statusResource).sendMessage(any(JsonRepresentation.class));
	}

	@AfterEach
	void afterEach() {
	}

	@Test
	@Disabled("Testing Restlet Tests")
	void successSendMessageTest() throws MessagingException, IOException {
		when(pm.getPrincipalEntry("bjorn@meta.se")).thenReturn(entry);
		when(pm.currentUserIsGuest()).thenReturn(false);

		String json =
				"""
				{
					"to": "bjorn@meta.se",
					"subject": "About tomorrow",
					"body": "Hi! See you tomorrow!",
					"transport": "EMAIL"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
//		statusResource.sendMessage(jsonRepresentation);

		assertThat(response.getStatus()).isEqualTo(SUCCESS_OK);
	}
}
