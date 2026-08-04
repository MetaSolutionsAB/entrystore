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

import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		assertEquals(expectedDomain, MailTemplateRenderer.resolveBaseUrlHost(configWithBaseUrl(baseUrl)));
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

		assertNotNull(new MailTemplateRenderer(new PropertiesConfiguration("test"))
						.render(template, Collections.emptyMap()),
				"the bundled fallback for " + template + " must render");
	}

	@Test
	void render_substitutesYearAndDomain() {
		assertPasswordChangeTemplateOnClasspath();
		Config config = configWithBaseUrl("https://entrystore.example/store/");

		String rendered = new MailTemplateRenderer(config)
				.render(EmailTemplate.PASSWORD_CHANGE, Map.of("__NAME__", "Ada"));

		assertNotNull(rendered);
		// Year.now(), not Calendar.getInstance(): asserting with the same locale-dependent call the
		// production code used to make would agree with it even when both render 2569 under a th-TH default.
		assertTrue(rendered.contains(Integer.toString(Year.now().getValue())),
				"__YEAR__ must be substituted with the current Gregorian year");
		assertTrue(rendered.contains("entrystore.example"), "__DOMAIN__ must be substituted with the base URL host");
		assertFalseContains(rendered, "__YEAR__");
		assertFalseContains(rendered, "__DOMAIN__");
		assertFalseContains(rendered, "__NAME__");
	}

	@Test
	void render_substitutionValueWithRegexMetacharacters_isInsertedLiterally(@TempDir Path tmp) throws IOException {
		// Guards the replaceAll -> replace change: with the regex form, "$1" in the *replacement* is a
		// group reference (IllegalArgumentException here, since the pattern has no groups) and a
		// backslash escapes the next character. Both must now survive verbatim.
		Config config = configWithTemplate(tmp, EmailTemplate.SIGNUP, "Hello __NAME__!");

		String rendered = new MailTemplateRenderer(config)
				.render(EmailTemplate.SIGNUP, Map.of("__NAME__", "$1 \\ backslash"));

		assertEquals("Hello $1 \\ backslash!", rendered);
	}

	@Test
	void render_nullSubstitutionValue_leavesThePlaceholderInPlace(@TempDir Path tmp) throws IOException {
		Config config = configWithTemplate(tmp, EmailTemplate.SIGNUP, "Hello __NAME__ at __EMAIL__");
		Map<String, String> substitutions = new HashMap<>();
		substitutions.put("__NAME__", null);
		substitutions.put("__EMAIL__", "ada@example.com");

		String rendered = new MailTemplateRenderer(config).render(EmailTemplate.SIGNUP, substitutions);

		assertEquals("Hello __NAME__ at ada@example.com", rendered);
	}

	@Test
	void render_unreadableTemplate_returnsNullAndIsRetriedOnTheNextCall(@TempDir Path tmp) throws IOException {
		// A failed load must not be cached: the previous implementation left its static cache field null
		// so the next send tried again, and an operator fixing the path should not need a restart.
		Path template = tmp.resolve("signup.html");
		Config config = new PropertiesConfiguration("test");
		config.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(), template.toString());
		MailTemplateRenderer renderer = new MailTemplateRenderer(config);

		assertNull(renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"a template path that cannot be read must render to null");

		Files.writeString(template, "now readable");

		assertEquals("now readable", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"the failed load must not have been cached");
	}

	@Test
	void render_readableTemplate_isCachedAndSurvivesTheSourceDisappearing(@TempDir Path tmp) throws IOException {
		Config config = configWithTemplate(tmp, EmailTemplate.SIGNUP, "cached body");
		MailTemplateRenderer renderer = new MailTemplateRenderer(config);

		assertEquals("cached body", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()));
		Files.delete(Path.of(config.getString(EmailTemplate.SIGNUP.getTemplatePathKey())));

		assertEquals("cached body", renderer.render(EmailTemplate.SIGNUP, Collections.emptyMap()),
				"a successfully loaded template must be served from the cache");
	}

	@Test
	void subject_fallsBackToTheDocumentedDefault() {
		Config config = new PropertiesConfiguration("test");

		assertEquals(EmailTemplate.SIGNUP.getDefaultSubject(),
				new MailTemplateRenderer(config).subject(EmailTemplate.SIGNUP));
	}

	@Test
	void subject_prefersTheConfiguredValue() {
		Config config = new PropertiesConfiguration("test");
		config.setProperty(EmailTemplate.SIGNUP.getSubjectKey(), "Welcome aboard");

		assertEquals("Welcome aboard", new MailTemplateRenderer(config).subject(EmailTemplate.SIGNUP));
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
			Config config = new PropertiesConfiguration("test");
			config.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/template.html");
			MailTemplateRenderer renderer = new MailTemplateRenderer(config);

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
	void render_unreachableHttpTemplate_returnsNullWithoutHangingIndefinitely() throws IOException {
		// A server that accepts the connection and never answers. The read timeout is what stops this from
		// pinning the calling thread forever, since a failed load is deliberately not cached.
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/stalled.html", exchange -> {
			// Never respond, never close: the connection stays open with no bytes written.
		});
		server.start();
		try {
			Config config = new PropertiesConfiguration("test");
			config.setProperty(EmailTemplate.SIGNUP.getTemplatePathKey(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/stalled.html");

			long startedAt = System.nanoTime();
			assertNull(new MailTemplateRenderer(config).render(EmailTemplate.SIGNUP, Collections.emptyMap()),
					"a template that cannot be fetched must render to null");
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

			// The configured timeout is 5s; anything near or beyond twice that means it was not applied.
			assertTrue(elapsedMs < 10_000, "the fetch must be bounded by the read timeout, took " + elapsedMs + "ms");
		} finally {
			server.stop(0);
		}
	}

	private static Config configWithTemplate(Path tmp, EmailTemplate template, String body) throws IOException {
		Path file = tmp.resolve(template.getClasspathResource());
		Files.writeString(file, body);
		Config config = new PropertiesConfiguration("test");
		config.setProperty(template.getTemplatePathKey(), file.toString());
		return config;
	}

	private static Config configWithBaseUrl(String baseUrl) {
		Config config = new PropertiesConfiguration("test");
		if (baseUrl != null) {
			config.setProperty(Settings.BASE_URL, baseUrl);
		}
		return config;
	}

	private static void assertPasswordChangeTemplateOnClasspath() {
		assertNotNull(Thread.currentThread().getContextClassLoader().getResourceAsStream("email_pwchange.html"),
				"email_pwchange.html must be on the test classpath, otherwise these tests are vacuous");
	}

	private static void assertFalseContains(String haystack, String needle) {
		assertTrue(!haystack.contains(needle), () -> "'" + needle + "' should have been substituted away");
	}
}
