package org.entrystore.rest.resources;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import org.entrystore.Entry;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.PrincipalManagerImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.restlet.data.Status.CLIENT_ERROR_BAD_REQUEST;
import static org.restlet.data.Status.SUCCESS_OK;

@ExtendWith(MockitoExtension.class)
class PasswordResetResourceTest {

	@RegisterExtension
	static GreenMailExtension mail = new GreenMailExtension(SMTP);

	@Mock
	RepositoryManagerImpl rm;
	@Mock
	PrincipalManagerImpl pm;
	@Mock
	LoginTokenCache ltc;
	@Mock
	EntryStoreApplication app;
	@Mock
	User user;
	@Mock
	Entry entry;
	@Mock
	PasswordResetResource passwordResetResource;
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
		config.setProperty(Settings.AUTH_PASSWORD_RESET, "on");
		config.setProperty(Settings.AUTH_PASSWORD_RESET_SUBJECT, "Password reset request");
		config.setProperty(Settings.AUTH_PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH, "src/test/resources/email_pwreset.html");
		config.setProperty(Settings.AUTH_PASSWORD_CHANGE_SUBJECT, "Your password has been changed");
		config.setProperty(Settings.AUTH_PASSWORD_CHANGE_CONFIRMATION_MESSAGE_TEMPLATE_PATH, "src/test/resources/email_pwchange.html");

		return config;
	}

	@BeforeEach
	void beforeEach() throws MalformedURLException {
		Config config = getConfig();

		Request request = new Request();
		request.setResourceRef("");
		this.response = new Response(request);

		lenient().when(passwordResetResource.getApplication()).thenReturn(app);
		lenient().when(app.getLoginTokenCache()).thenReturn(ltc);
		lenient().when(passwordResetResource.getPM()).thenReturn(pm);
		lenient().when(passwordResetResource.getRM()).thenReturn(rm);
		lenient().when(rm.getConfiguration()).thenReturn(config);
		lenient().when(rm.getRepositoryURL()).thenReturn(URI.create(config.getString(Settings.BASE_URL)).toURL());
		lenient().when(passwordResetResource.getRequest()).thenReturn(request);
		lenient().when(passwordResetResource.getResponse()).thenReturn(response);
		lenient().doCallRealMethod().when(passwordResetResource).init(any(Context.class), any(Request.class), any(Response.class));
		lenient().doCallRealMethod().when(passwordResetResource).acceptRepresentation(any(JsonRepresentation.class));
		lenient().doCallRealMethod().when(passwordResetResource).represent();
		lenient().when(pm.getUserByExternalID("test@meta.se")).thenReturn(user);
		lenient().when(pm.getAdminUser()).thenReturn(user);
		lenient().when(user.getName()).thenReturn("test@meta.se");
		lenient().when(user.getEntry()).thenReturn(entry);
		lenient().when(entry.getResource()).thenReturn(user);
		lenient().when(user.setSaltedHashedSecret(any(String.class))).thenReturn(true);
	}

	@AfterEach
	void afterEach() {
		mail.reset();
	}

	@Test
	void acceptRepresentation_ok() throws MessagingException, IOException {
		String json =
			"""
				{
					"email": "test@meta.se",
					"password": "Password1234"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
		passwordResetResource.acceptRepresentation(jsonRepresentation);
		assertThat(response.getStatus()).isEqualTo(SUCCESS_OK);
		MimeMessage[] messages = mail.getReceivedMessages();
		assertThat(messages).hasSize(1);
		MimeMessage message = messages[0];
		assertThat(message.getFrom()).containsExactly(new InternetAddress("info@meta.se"));
		assertThat(message.getSubject()).isEqualTo("Password reset request");
		assertThat(message.getReplyTo()).containsExactly(new InternetAddress("info@meta.se"));
		assertThat(message.getAllRecipients()).containsExactly(new InternetAddress("test@meta.se"));
		assertThat(message.getContent()).asString().contains("auth/pwreset?confirm=");
	}

	@Test
	void represent_ok() throws MessagingException, IOException {
		String json =
			"""
				{
					"email": "test@meta.se",
					"password": "Password1234"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
		passwordResetResource.acceptRepresentation(jsonRepresentation);
		MimeMessage resetMessage = mail.getReceivedMessages()[0];
		assertThat(resetMessage.getContent()).asString().contains("auth/pwreset?confirm=");
		int startIndex = resetMessage.getContent().toString().indexOf("?confirm") + 9;
		String token = resetMessage.getContent().toString().substring(startIndex, startIndex + 16);
		response.getRequest().setEntity(new EmptyRepresentation());
		response.getRequest().getResourceRef().addQueryParameter("confirm", token);
		passwordResetResource.init(new Context(), response.getRequest(), response);
		passwordResetResource.represent();
		assertThat(response.getStatus()).isEqualTo(SUCCESS_OK);
		MimeMessage[] messages = mail.getReceivedMessages();
		assertThat(messages).hasSize(2);
		assertThat(messages[0].getContent()).asString().contains("auth/pwreset?confirm=");
		MimeMessage message = messages[1];
		assertThat(message.getFrom()).containsExactly(new InternetAddress("info@meta.se"));
		assertThat(message.getSubject()).isEqualTo("Your password has been changed");
		assertThat(message.getAllRecipients()).containsExactly(new InternetAddress("test@meta.se"));
	}

	@Test
	void represent_expired() throws MessagingException, IOException {
		String instantExpected = "2099-12-22T10:15:30Z";
		Clock clock = Clock.fixed(Instant.parse(instantExpected), ZoneId.of("UTC"));
		Instant instant = Instant.now(clock);

		String json =
			"""
				{
					"email": "test@meta.se",
					"password": "Password1234"
				}
				""";
		JsonRepresentation jsonRepresentation = new JsonRepresentation(json);
		response.getRequest().setEntity(jsonRepresentation);
		passwordResetResource.acceptRepresentation(jsonRepresentation);
		try (MockedStatic<Instant> mockedStatic = mockStatic(Instant.class)) {
			mockedStatic.when(Instant::now).thenReturn(instant);
			MimeMessage resetMessage = mail.getReceivedMessages()[0];
			assertThat(resetMessage.getContent()).asString().contains("auth/pwreset?confirm=");
			int startIndex = resetMessage.getContent().toString().indexOf("?confirm") + 9;
			String token = resetMessage.getContent().toString().substring(startIndex, startIndex + 16);
			response.getRequest().setEntity(new EmptyRepresentation());
			response.getRequest().getResourceRef().addQueryParameter("confirm", token);
			passwordResetResource.init(new Context(), response.getRequest(), response);
			passwordResetResource.represent();
			assertThat(response.getStatus()).isEqualTo(CLIENT_ERROR_BAD_REQUEST);
		}

	}
}
