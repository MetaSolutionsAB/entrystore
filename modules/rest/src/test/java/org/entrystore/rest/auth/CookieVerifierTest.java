package org.entrystore.rest.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.BeforeEach;

class CookieVerifierTest {

	private RepositoryManager rm;
	private PrincipalManager pm;

	@BeforeEach
	public void beforeEach() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_COOKIE_PATH, "/");
		config.setProperty(Settings.AUTH_COOKIE_UPDATE_EXPIRY, "on");

		this.rm = mock(RepositoryManager.class);
		this.pm = mock(PrincipalManager.class);

		when(rm.getConfiguration()).thenReturn(config);
		when(rm.getPrincipalManager()).thenReturn(pm);
		when(pm.getAdminUser()).thenReturn(mock(User.class));
		when(pm.getGuestUser()).thenReturn(mock(User.class));
	}

//	@Test
//	public void updateExpiry() throws InterruptedException {
//		rm.getConfiguration().setProperty(Settings.AUTH_COOKIE_UPDATE_EXPIRY, "on");
//
//		ExpiryDates result = callCookieVerifier();
//
//		assertThat(result.firstExpiration()).isBefore(result.secondExpiration());
//	}
//
//	@Test
//	public void doNotUpdateExpiry() throws InterruptedException {
//		rm.getConfiguration().setProperty(Settings.AUTH_COOKIE_UPDATE_EXPIRY, "off");
//
//		ExpiryDates result = callCookieVerifier();
//
//		assertThat(result.firstExpiration()).isEqualTo(result.secondExpiration());
//	}
//
//	private ExpiryDates callCookieVerifier() throws InterruptedException {
//		Entry entry = mock(Entry.class);
//		when(pm.getPrincipalEntry(eq("test"))).thenReturn(entry);
//
//		CookieVerifier cookieVerifier = new CookieVerifier(rm, null);
//
//		Request request = new Request();
//		request.setResourceRef("");
//		Response response = new Response(request);
//
//		cookieVerifier.createAuthToken("test", false, request, response);
//		CookieSetting authToken = response.getCookieSettings().getFirst("auth_token");
//		String tokenValue = StringUtils.substringBefore(authToken.getValue(), ";");
//		LocalDateTime firstExpiration = LoginTokenCache.getInstance().getTokenValue(tokenValue).getLoginExpiration();
//
//		Thread.sleep(50);
//		request.getCookies().add("auth_token", tokenValue);
//		cookieVerifier.verify(request, response);
//		LocalDateTime secondExpiration = LoginTokenCache.getInstance().getTokenValue(tokenValue).getLoginExpiration();
//
//		return new ExpiryDates(firstExpiration, secondExpiration);
//	}

	record ExpiryDates(LocalDateTime firstExpiration, LocalDateTime secondExpiration) {}
}
