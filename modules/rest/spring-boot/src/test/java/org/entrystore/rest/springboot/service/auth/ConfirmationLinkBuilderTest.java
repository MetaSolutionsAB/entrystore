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

package org.entrystore.rest.springboot.service.auth;

import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.ConfirmationUrlProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.MalformedURLException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfirmationLinkBuilderTest {

	private static final String TOKEN = "Ab3dEf6hIj9lMn2p";

	@Test
	void unsetTargets_linkToTheBackendConfirmationPages() throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(null, null));

		assertEquals("https://store.example.com/store/auth/pwreset?confirm=" + TOKEN,
				builder.passwordResetLink(TOKEN));
		assertEquals("https://store.example.com/store/auth/signup?confirm=" + TOKEN, builder.signupLink(TOKEN));
	}

	@Test
	void configuredTarget_getsTheTokenSubstituted() throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(
				"https://app.example.com/resetpassword?confirm=__CONFIRMATION_TOKEN__",
				"https://app.example.com/confirmsignup?confirm=__CONFIRMATION_TOKEN__"));

		assertEquals("https://app.example.com/resetpassword?confirm=" + TOKEN, builder.passwordResetLink(TOKEN));
		assertEquals("https://app.example.com/confirmsignup?confirm=" + TOKEN, builder.signupLink(TOKEN));
	}

	@Test
	void flowsAreConfiguredIndependently() throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(
				null, "https://app.example.com/confirmsignup?confirm=__CONFIRMATION_TOKEN__"));

		assertEquals("https://store.example.com/store/auth/pwreset?confirm=" + TOKEN,
				builder.passwordResetLink(TOKEN));
		assertEquals("https://app.example.com/confirmsignup?confirm=" + TOKEN, builder.signupLink(TOKEN));
	}

	@Test
	void everyPlaceholderIsReplacedAndOtherParametersSurvive() throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(
				"https://app.example.com/reset/__CONFIRMATION_TOKEN__?lang=sv&confirm=__CONFIRMATION_TOKEN__", null));

		assertEquals("https://app.example.com/reset/" + TOKEN + "?lang=sv&confirm=" + TOKEN,
				builder.passwordResetLink(TOKEN));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"/resetpassword?confirm=__CONFIRMATION_TOKEN__, https://store.example.com/resetpassword?confirm=",
			"resetpassword?confirm=__CONFIRMATION_TOKEN__, https://store.example.com/store/resetpassword?confirm=",
			"/#/resetpassword?confirm=__CONFIRMATION_TOKEN__, https://store.example.com/#/resetpassword?confirm=",
			"https://app.example.com/resetpassword?confirm=__CONFIRMATION_TOKEN__, "
					+ "https://app.example.com/resetpassword?confirm="
	})
	void relativeTarget_isResolvedAgainstTheBaseUrl(String template, String expectedLinkBeforeToken)
			throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(template, null));

		assertEquals(expectedLinkBeforeToken + TOKEN, builder.passwordResetLink(TOKEN));
	}

	@Test
	void baseUrlWithoutTrailingSlash_isNormalisedBeforeResolving() throws MalformedURLException {
		var builder = builder(new ConfirmationUrlProperties(null, "confirmsignup?confirm=__CONFIRMATION_TOKEN__"),
				"https://store.example.com/store");

		assertEquals("https://store.example.com/store/confirmsignup?confirm=" + TOKEN, builder.signupLink(TOKEN));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
			"ftp://example.com/resetpassword?confirm=__CONFIRMATION_TOKEN__",
			"javascript:alert(1)//__CONFIRMATION_TOKEN__",
			"https:///resetpassword?confirm=__CONFIRMATION_TOKEN__"
	})
	void targetThatDoesNotResolveToAnHttpUrl_failsNamingTheKey(String template) {
		var e = assertThrows(IllegalArgumentException.class,
				() -> builder(new ConfirmationUrlProperties(template, null)));

		assertTrue(e.getMessage().contains("entrystore.auth.confirmation.url.pwreset must resolve to an http(s) URL"),
				e.getMessage());
	}

	private static ConfirmationLinkBuilder builder(ConfirmationUrlProperties urls) throws MalformedURLException {
		return builder(urls, "https://store.example.com/store/");
	}

	private static ConfirmationLinkBuilder builder(ConfirmationUrlProperties urls, String baseUrl)
			throws MalformedURLException {
		RepositoryManagerImpl repositoryManager = mock(RepositoryManagerImpl.class);
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create(baseUrl).toURL());
		return new ConfirmationLinkBuilder(urls, repositoryManager);
	}
}
