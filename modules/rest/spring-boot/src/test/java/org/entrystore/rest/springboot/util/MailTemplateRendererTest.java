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

package org.entrystore.rest.springboot.util;

import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailTemplateRendererTest {

	@ParameterizedTest(name = "[{0}] -> \"{1}\"")
	@CsvSource(nullValues = "NULL", value = {
			"NULL,                              ''",
			"'   ',                             ''",
			"store,                             ''",
			"ht tp://bad,                       ''",
			"https://entrystore.example/store/, entrystore.example"
	})
	void resolveBaseUrlHost_returnsHostOrEmpty(String baseUrl, String expectedDomain) {
		assertEquals(expectedDomain, MailTemplateRenderer.resolveBaseUrlHost(environmentWithBaseUrl(baseUrl)));
	}

	@ParameterizedTest(name = "{0} bundled fallback renders")
	@EnumSource(EmailTemplate.class)
	void everyTemplate_hasABundledFallbackThatRenders(EmailTemplate template) {
		// Only email_pwchange.html was previously asserted present, so a typo in either other literal —
		// or the resource ceasing to be packaged — would break mail for every deployment that does not
		// configure an explicit template path, i.e. the default configuration, with all tests green.
		assertNotNull(Thread.currentThread().getContextClassLoader()
						.getResourceAsStream(template.getClasspathResource()),
				template.getClasspathResource() + " must be on the classpath");

		assertNotNull(new MailTemplateRenderer(new MockEnvironment())
						.render(template, Collections.emptyMap()),
				"the bundled fallback for " + template + " must render");
	}

	@Test
	void render_substitutesYearAndDomain() {
		assertPasswordChangeTemplateOnClasspath();
		MockEnvironment environment = environmentWithBaseUrl("https://entrystore.example/store/");

		String rendered = new MailTemplateRenderer(environment)
				.render(EmailTemplate.PASSWORD_CHANGE, Map.of("__NAME__", "Ada"));

		assertNotNull(rendered);
		assertTrue(rendered.contains(Integer.toString(Year.now().getValue())),
				"__YEAR__ must be substituted with the current Gregorian year");
		assertTrue(rendered.contains("entrystore.example"), "__DOMAIN__ must be substituted with the base URL host");
		assertFalseContains(rendered, "__YEAR__");
		assertFalseContains(rendered, "__DOMAIN__");
		assertFalseContains(rendered, "__NAME__");
	}

	@Test
	void render_underANonGregorianDefaultLocale_stillSubstitutesTheGregorianYear() throws IOException {
		// The locale has to be driven for this to mean anything. Asserting against Year.now() alone is the
		// identical call production makes, so reverting to Calendar.getInstance() stayed green on every
		// Gregorian-default machine — which is every developer machine and the CI runner. Under a th-TH
		// default, Calendar.getInstance() returns a BuddhistCalendar whose YEAR field is 543 higher.
		Locale previousDefault = Locale.getDefault();
		try {
			Locale.setDefault(Locale.of("th", "TH"));
			int gregorianYear = Year.now().getValue();
			// Exactly what the pre-fix production line produced under this default.
			int buddhistYear = Calendar.getInstance().get(Calendar.YEAR);
			assertNotEquals(gregorianYear, buddhistYear,
					"this JVM does not give th-TH a Buddhist calendar, so the test cannot prove anything");

			String rendered = new MailTemplateRenderer(environmentWithBaseUrl("https://entrystore.example/"))
					.render(EmailTemplate.PASSWORD_CHANGE, Map.of("__NAME__", "Ada"));

			assertNotNull(rendered);
			assertTrue(rendered.contains(Integer.toString(gregorianYear)),
					"__YEAR__ must be the Gregorian year regardless of the default locale's calendar");
			assertFalseContains(rendered, Integer.toString(buddhistYear));
		} finally {
			Locale.setDefault(previousDefault);
		}
	}

	@Test
	void render_substitutionValueWithRegexMetacharacters_isInsertedLiterally(@TempDir Path tmp) throws IOException {
		// Guards the replaceAll -> replace change: with the regex form, "$1" in the *replacement* is a
		// group reference (IllegalArgumentException here, since the pattern has no groups) and a
		// backslash escapes the next character. Both must now survive verbatim.
		MockEnvironment environment = environmentWithTemplate(tmp, EmailTemplate.SIGNUP, "Hello __NAME__!");

		String rendered = new MailTemplateRenderer(environment)
				.render(EmailTemplate.SIGNUP, Map.of("__NAME__", "$1 \\ backslash"));

		assertEquals("Hello $1 \\ backslash!", rendered);
	}

	@Test
	void render_nullSubstitutionValue_leavesThePlaceholderInPlace(@TempDir Path tmp) throws IOException {
		MockEnvironment environment = environmentWithTemplate(tmp, EmailTemplate.SIGNUP, "Hello __NAME__ at __EMAIL__");
		Map<String, String> substitutions = new HashMap<>();
		substitutions.put("__NAME__", null);
		substitutions.put("__EMAIL__", "ada@example.com");

		String rendered = new MailTemplateRenderer(environment).render(EmailTemplate.SIGNUP, substitutions);

		assertEquals("Hello __NAME__ at ada@example.com", rendered);
	}

	@Test
	void render_unreadableTemplate_returnsNullAndIsRetriedOnTheNextCall(@TempDir Path tmp) throws IOException {
		// A failed load must not be cached: the previous implementation left its static cache field null
		// so the next send tried again, and an operator fixing the path should not need a restart.
		Path template = tmp.resolve("signup.html");
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(), template.toString());
		MailTemplateRenderer renderer = new MailTemplateRenderer(environment);

		assertNull(renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"a template path that cannot be read must render to null");

		Files.writeString(template, "now readable");

		assertEquals("now readable", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"the failed load must not have been cached");
	}

	@Test
	void render_readableTemplate_isCachedAndSurvivesTheSourceDisappearing(@TempDir Path tmp) throws IOException {
		MockEnvironment environment = environmentWithTemplate(tmp, EmailTemplate.SIGNUP, "cached body");
		MailTemplateRenderer renderer = new MailTemplateRenderer(environment);

		assertEquals("cached body", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()));
		Files.delete(Path.of(environment.getProperty(EmailTemplate.SIGNUP.getTemplatePathKey())));

		assertEquals("cached body", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"a successfully loaded template must be served from the cache");
	}

	@Test
	void subject_fallsBackToTheDocumentedDefault() {
		assertEquals(EmailTemplate.SIGNUP.getDefaultSubject(),
				new MailTemplateRenderer(new MockEnvironment()).subject(EmailTemplate.SIGNUP));
	}

	@Test
	void subject_prefersTheConfiguredValue() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(EmailTemplate.SIGNUP.getSubjectKey(), "Welcome aboard");

		assertEquals("Welcome aboard", new MailTemplateRenderer(environment).subject(EmailTemplate.SIGNUP));
	}

	@Test
	void render_httpTemplateUrl_isFetchedAndCached() throws IOException {
		// The http(s) branch of openTemplate was never exercised, so nothing proved a configured URL is
		// fetched at all — only that a filesystem path is.
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/template.html", exchange -> {
			byte[] body = "Hello __NAME__ from HTTP".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		try {
			MockEnvironment environment = new MockEnvironment();
			environment.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/template.html");
			MailTemplateRenderer renderer = new MailTemplateRenderer(environment);

			assertEquals("Hello Ada from HTTP",
					renderer.render(EmailTemplate.SIGNUP, Map.of("__NAME__", "Ada")));

			server.stop(0);
			assertEquals("Hello Grace from HTTP",
					renderer.render(EmailTemplate.SIGNUP, Map.of("__NAME__", "Grace")),
					"the fetched template must be cached rather than re-fetched per send");
		} finally {
			server.stop(0);
		}
	}

	@Test
	@Timeout(60)
	void render_unreachableHttpTemplate_returnsNullWithoutHangingIndefinitely() throws IOException {
		// A server that accepts the connection and never answers. The read timeout is what stops this from
		// pinning the calling thread forever, since a failed load is deliberately not cached.
		//
		// @Timeout is the build's protection, not a nicety: this module sets neither a Surefire fork timeout
		// nor junit.jupiter.execution.timeout.default, so without it a removed setReadTimeout would hang the
		// build indefinitely instead of failing this test.
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stalled.html", exchange -> {
			// Never respond, never close: the connection stays open with no bytes written.
		});
		server.start();
		try {
			MockEnvironment environment = new MockEnvironment();
			environment.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/stalled.html");

			long startedAt = System.nanoTime();
			assertNull(new MailTemplateRenderer(environment).render(EmailTemplate.SIGNUP, Collections.emptyMap()),
					"a template that cannot be fetched must render to null");
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			// Bounded above and below. The upper bound (the configured timeout is 5s) shows a timeout was
			// applied at all; the lower bound is what distinguishes "the read timeout fired" from "the
			// server answered and nothing ever stalled", which an upper bound alone cannot tell apart.
			assertTrue(elapsedMs < 10_000, "the fetch must be bounded by the read timeout, took " + elapsedMs + "ms");
			assertTrue(elapsedMs >= 4_000, "the read must actually have stalled until the timeout, took "
					+ elapsedMs + "ms — a passing upper bound alone would not prove the timeout fired");
		} finally {
			server.stop(0);
		}
	}

	private static MockEnvironment environmentWithTemplate(Path tmp, EmailTemplate template, String body)
			throws IOException {
		Path file = tmp.resolve(template.getClasspathResource());
		Files.writeString(file, body);
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(template.getTemplatePathKey(), file.toString());
		return environment;
	}

	private static MockEnvironment environmentWithBaseUrl(String baseUrl) {
		MockEnvironment environment = new MockEnvironment();
		if (baseUrl != null) {
			environment.setProperty(Settings.BASE_URL, baseUrl);
		}
		return environment;
	}

	private static void assertPasswordChangeTemplateOnClasspath() {
		assertNotNull(Thread.currentThread().getContextClassLoader().getResourceAsStream("email_pwchange.html"),
				"email_pwchange.html must be on the test classpath, otherwise these tests are vacuous");
	}

	private static void assertFalseContains(String haystack, String needle) {
		assertTrue(!haystack.contains(needle), () -> "'" + needle + "' should have been substituted away");
	}
}
