package org.entrystore.rest.resources;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.impl.PrincipalManagerImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.ext.json.JsonRepresentation;
import org.skyscreamer.jsonassert.JSONAssert;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.restlet.data.Status.CLIENT_ERROR_FORBIDDEN;
import static org.restlet.data.Status.CLIENT_ERROR_UNAUTHORIZED;
import static org.restlet.data.Status.SUCCESS_OK;

@ExtendWith(MockitoExtension.class)
class MessageResourceTest {

	@RegisterExtension static GreenMailExtension mail = new GreenMailExtension(SMTP);

	@Mock EntryStoreApplication app;
	@Mock RepositoryManagerImpl rm;
	@Mock PrincipalManagerImpl pm;
	@Mock Context context;
	@Mock Entry entry;
	@Mock MessageResource messageResource;
	Response response;

	private static @NotNull Config getConfig() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_COOKIE_PATH, "/");
		config.setProperty(Settings.SMTP_EMAIL_FROM, "info@meta.se");
		config.setProperty(Settings.SMTP_HOST, "localhost");
		config.setProperty(Settings.SMTP_PORT, mail.getSmtp().getPort());
		return config;
	}

	@BeforeEach
	void beforeEach() {
		Config config = getConfig();

		Request request = new Request();
		request.setResourceRef("");
		this.response = new Response(request);

		lenient().when(messageResource.getPM()).thenReturn(pm);
		lenient().when(messageResource.getRM()).thenReturn(rm);
		lenient().when(rm.getConfiguration()).thenReturn(config);
		lenient().when(messageResource.getResponse()).thenReturn(response);
		lenient().when(messageResource.getRequest()).thenReturn(request);
		lenient().doCallRealMethod().when(messageResource).unauthorizedPOST();
		doCallRealMethod().when(messageResource).sendMessage(any(JsonRepresentation.class));
	}

	@AfterEach
	void afterEach() {
		mail.reset();
	}

	@Test
	void successSendMessageTest() throws MessagingException, IOException {
		when(pm.getPrincipalEntry("test@meta.se")).thenReturn(entry);
		when(pm.currentUserIsGuest()).thenReturn(false);

		String json =
				"""
				{
					"to": "test@meta.se",
					"subject": "About tomorrow",
					"body": "Hi! See you tomorrow!",
					"transport": "email"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
		messageResource.sendMessage(jsonRepresentation);

		assertThat(response.getStatus()).isEqualTo(SUCCESS_OK);
		MimeMessage[] messages = mail.getReceivedMessages();
		assertThat(messages).hasSize(1);
		MimeMessage message = messages[0];
		assertThat(message.getFrom()).containsExactly(new InternetAddress("info@meta.se"));
		assertThat(message.getSubject()).isEqualTo("About tomorrow");
		assertThat(message.getReplyTo()).containsExactly(new InternetAddress("info@meta.se"));
		assertThat(message.getAllRecipients()).containsExactly(new InternetAddress("test@meta.se"));
		assertThat(message.getContent()).isInstanceOf(String.class).isEqualTo("Hi! See you tomorrow!");
	}

	@Test
	void userNotAllowedToSendMessageTest() {
		when(pm.getPrincipalEntry("nonexistinguser@moto.se")).thenReturn(null);
		when(pm.currentUserIsGuest()).thenReturn(false);

		String json =
				"""
				{
					"to": "nonexistinguser@moto.se",
					"subject": "About tomorrow",
					"body": "Hi! See you tomorrow!",
					"transport": "EMAIL"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);

		messageResource.sendMessage(jsonRepresentation);

		assertThat(response.getStatus()).isEqualTo(CLIENT_ERROR_FORBIDDEN);
		MimeMessage[] messages = mail.getReceivedMessages();
		assertThat(messages).hasSize(0);
	}

	@Test
	void guestUserShouldNotBeAbleToSendMessageTest() throws IOException {
		when(pm.currentUserIsGuest()).thenReturn(true);

		String json =
				"""
				{
					"to": "nonexistinguser@moto.se",
					"subject": "About tomorrow",
					"body": "Hi! See you tomorrow!",
					"transport": "EMAIL"
				}
				""";

		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
		messageResource.sendMessage(jsonRepresentation);

		assertThat(response.getStatus()).isEqualTo(CLIENT_ERROR_UNAUTHORIZED);
		MimeMessage[] messages = mail.getReceivedMessages();
		assertThat(messages).hasSize(0);
		String expectedJson  = """
			{"error":"Not authorized"}
		""";
		JSONAssert.assertEquals(expectedJson, response.getEntity().getText(), true);
	}

}
