package org.entrystore.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entrystore.rest.util.HttpUtil.COOKIE_AUTH_TOKEN;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.EntryStoreApplication;
import org.junit.jupiter.api.Test;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CookieSetting;

class CookieVerifierTest {

	@Test
	public void updateExpiry() throws InterruptedException {
		ExpiryDates result = callCookieVerifier("on");
		assertThat(result.firstExpiration()).isBefore(result.secondExpiration());
	}

	@Test
	public void doNotUpdateExpiry() throws InterruptedException {
		ExpiryDates result = callCookieVerifier("off");
		assertThat(result.firstExpiration()).isEqualTo(result.secondExpiration());
	}

	private ExpiryDates callCookieVerifier(String authCookieUpdateExpiry) throws InterruptedException {

		EntryStoreApplication app = mock(EntryStoreApplication.class);
		RepositoryManager rm = mock(RepositoryManager.class);
		PrincipalManager pm = mock(PrincipalManager.class);
		Entry entry = mock(Entry.class);

		Config config = getConfig(authCookieUpdateExpiry);
		LoginTokenCache loginTokenCache = new LoginTokenCache(config);

		when(rm.getPrincipalManager()).thenReturn(pm);
		when(rm.getConfiguration()).thenReturn(config);
		when(pm.getPrincipalEntry(eq("test"))).thenReturn(entry);
		when(pm.getAdminUser()).thenReturn(mock(User.class));
		when(pm.getGuestUser()).thenReturn(mock(User.class));
		when(app.getLoginTokenCache()).thenReturn(loginTokenCache);

		CookieVerifier cookieVerifier = new CookieVerifier(app, rm);

		Request request = new Request();
		request.setResourceRef("");
		Response response = new Response(request);

		cookieVerifier.createAuthToken("test", false, request, response);

		CookieSetting authToken = response.getCookieSettings().getFirst(COOKIE_AUTH_TOKEN);
		String tokenValue = StringUtils.substringBefore(authToken.getValue(), ";");
		LocalDateTime firstExpiration = app.getLoginTokenCache().getTokenValue(tokenValue).getLoginExpiration();

		Thread.sleep(50);

		request.getCookies().add(COOKIE_AUTH_TOKEN, tokenValue);
		cookieVerifier.verify(request, response);
		LocalDateTime secondExpiration = app.getLoginTokenCache().getTokenValue(tokenValue).getLoginExpiration();

		return new ExpiryDates(firstExpiration, secondExpiration);
	}

	private Config getConfig(String authCookieUpdateExpiry) {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_COOKIE_PATH, "/");
		config.setProperty(Settings.AUTH_COOKIE_UPDATE_EXPIRY, authCookieUpdateExpiry);
		return config;
	}

	record ExpiryDates(LocalDateTime firstExpiration, LocalDateTime secondExpiration) {}
}
