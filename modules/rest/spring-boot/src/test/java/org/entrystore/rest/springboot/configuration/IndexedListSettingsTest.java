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

package org.entrystore.rest.springboot.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the list {@link IndexedListSettings#read} returns, which is what every consumer applies, and the
 * WARN the post-processor logs. Environments are attached to Boot's configuration property sources, as
 * the application's is, so relaxed names and environment variables resolve as they do in production.
 */
class IndexedListSettingsTest {

	private final List<String> warnings = new ArrayList<>();

	private final List<String> infos = new ArrayList<>();

	// --- 5.x shapes in one source ---

	@Test
	void bareEmptyValue_isTheOneEmptyEntry5xRead() {
		// The entrystore-registry.properties shape: no host matches "", so nothing is whitelisted.
		var environment = file("entrystore.proxy.whitelist.local", "");

		assertEquals(List.of(""), startAndRead(environment, "entrystore.proxy.whitelist.local"));
		assertTrue(IndexedListSettings.readHosts(environment, "entrystore.proxy.whitelist.local").isEmpty());
		assertWarned("'entrystore.proxy.whitelist.local'", "[\"\"]", "remove the key to configure no list");
		assertTrue(warnings.stream().noneMatch(warning -> warning.contains("wins over indexed entries")),
				"with no indexed entries there is nothing the bare value wins over; got: " + warnings);
	}

	@Test
	void bareEmptySignupWhitelist_isANonEmptyWhitelistThatAdmitsNoDomain() {
		// 5.x read [""], which no email domain matches, so it refused every sign-up; [] would allow all.
		var environment = file("entrystore.auth.signup.whitelist", "");

		assertEquals(List.of(""), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void bareValue_isAOneElementList() {
		var environment = file("entrystore.proxy.whitelist.local", "Cache.Internal");

		assertEquals(List.of("Cache.Internal"), startAndRead(environment, "entrystore.proxy.whitelist.local"));
		assertEquals(List.of(), warnings, "a plain 5.x bare value is no cause for a WARN");
		assertInformed("'entrystore.proxy.whitelist.local'", "[\"Cache.Internal\"] from its bare value",
				"entrystore.proxy.whitelist.local.1=Cache.Internal");
	}

	@Test
	void bareValueAlongsideIndexedEntries_winsOverThem() {
		var environment = file(
				"entrystore.auth.password.blacklist", "blocked@test.com",
				"entrystore.auth.password.blacklist.1", "other@test.com",
				"entrystore.auth.password.blacklist.2", "third@test.com");

		assertEquals(List.of("blocked@test.com"), startAndRead(environment, "entrystore.auth.password.blacklist"));
		assertWarned("ignored: [entrystore.auth.password.blacklist.1, entrystore.auth.password.blacklist.2]",
				"entrystore.auth.password.blacklist.1=blocked@test.com");
	}

	@Test
	void indexGap_endsTheList() {
		var environment = file(
				"entrystore.auth.permitted.redirects.1", "https://one.example/",
				"entrystore.auth.permitted.redirects.3", "https://three.example/");

		assertEquals(List.of("https://one.example/"),
				startAndRead(environment, "entrystore.auth.permitted.redirects"));
		assertWarned("'entrystore.auth.permitted.redirects'", "[\"https://one.example/\"]",
				"ignored: [entrystore.auth.permitted.redirects.3]",
				"entrystore.auth.permitted.redirects.1=https://one.example/");
	}

	@Test
	void zeroIndex_isIgnored() {
		var environment = file(
				"entrystore.proxy.whitelist.anonymous.0", "evil.example",
				"entrystore.proxy.whitelist.anonymous.1", "guest.example");

		assertEquals(List.of("guest.example"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertWarned("ignored: [entrystore.proxy.whitelist.anonymous.0]");
	}

	@Test
	void zeroPaddedIndices_areIgnored() {
		// 5.x looked for the literal key .1, which .01 is not, so the list was empty.
		var environment = file(
				"entrystore.proxy.remote-resource.delete.whitelist.01", "https://one.example",
				"entrystore.proxy.remote-resource.delete.whitelist.02", "https://two.example");

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.remote-resource.delete.whitelist"));
		assertWarned("applies []", "entrystore.proxy.remote-resource.delete.whitelist.01, "
				+ "entrystore.proxy.remote-resource.delete.whitelist.02]",
				"entrystore.proxy.remote-resource.delete.whitelist.1, ");
	}

	@Test
	void nonNumericSuffix_isIgnored() {
		// .l (letter l) is a mistyped .1; the map binder alone would whitelist it.
		var environment = file(
				"entrystore.auth.password.whitelist.l", "contractor",
				"entrystore.auth.password.whitelist.1", "admin");

		assertEquals(List.of("admin"), startAndRead(environment, "entrystore.auth.password.whitelist"));
		assertWarned("ignored: [entrystore.auth.password.whitelist.l]");
	}

	@Test
	void relaxedSpellingWithNonNumericSuffix_isIgnored() {
		var environment = file("entrystore.proxy.remoteResource.delete.whitelist.evil", "https://attacker.example");

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.remote-resource.delete.whitelist"));
		assertWarned("ignored: [", "entrystore.proxy.remoteResource.delete.whitelist.evil");
	}

	@Test
	void suffixesSpringRelaxesToAnIndex_areIgnored() {
		// The binder resolves .2 to .2_ and .1 to .1-; 5.x read only the literal .1, .2, ...
		var environment = file(
				"entrystore.proxy.whitelist.anonymous.1", "a.example",
				"entrystore.proxy.whitelist.anonymous.2_", "b.example",
				"entrystore.proxy.whitelist.anonymous.1-", "d.example");

		assertEquals(List.of("a.example"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertWarned("entrystore.proxy.whitelist.anonymous.1-", "entrystore.proxy.whitelist.anonymous.2_");
	}

	@Test
	void leadingDashIndex_isIgnored() {
		// Boot's name drops the dash, so .-1 alone would otherwise count as .1. Next to a .1, Boot folds it into
		// that .1, so it is neither applied nor reported.
		var environment = file("entrystore.proxy.whitelist.anonymous.-1", "c.example");

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertWarned("entrystore.proxy.whitelist.anonymous.-1");
	}

	@Test
	void ignoredEntryOnTheCommandLine_doesNotDisplaceTheBareValueInTheFile() {
		// Displacing it would leave an empty sign-up whitelist, which admits every domain.
		var environment = withHigher(file("entrystore.auth.signup.whitelist", "company.example"),
				commandLine("entrystore.auth.signup.whitelist.l", "ignored.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.l]");
	}

	@Test
	void uppercaseAliasInAFile_losesToTheExactNameListedAfterIt() {
		// A config file is read by exact names, as 5.x read it.
		var environment = file(
				"entrystore.auth.signup.WHITE-LIST.1", "evil.example",
				"entrystore.auth.signup.whitelist.1", "good.example");

		assertEquals(List.of("good.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("good.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.WHITE-LIST.1]");
	}

	@Test
	void uppercaseAliasInAFile_losesToTheExactNameListedBeforeIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist.1", "good.example",
				"entrystore.auth.signup.WHITE-LIST.1", "evil.example");

		assertEquals(List.of("good.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("good.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.WHITE-LIST.1]");
	}

	@Test
	void lowercaseDashedAliasInAFile_isIgnoredWhereTheBinderTakesIt() {
		var environment = file(
				"entrystore.auth.signup.white-list.1", "first.example",
				"entrystore.auth.signup.whitelist.1", "second.example");

		assertEquals(List.of("second.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("first.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"),
				"the binder treats white-list and whitelist as one name and binds the one listed first");
		assertWarned("ignored: [entrystore.auth.signup.white-list.1]");
	}

	@Test
	void relaxedSpellingAloneInAFile_isIgnoredAs5xIgnoredIt() {
		var environment = file(
				"entrystore.auth.signup.WHITELIST.1", "upper.example",
				"entrystore.auth.signup.white-list.2", "dashed.example");

		assertEquals(List.of(), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("upper.example", "dashed.example"),
				boundByBinder(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("entrystore.auth.signup.WHITELIST.1", "entrystore.auth.signup.white-list.2");
	}

	@Test
	void trailingDashIndexListedBeforeTheIndexInAFile_doesNotHideIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist.1-", "ignored.example",
				"entrystore.auth.signup.whitelist.1", "company.example");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.1-]");
	}

	@Test
	void trailingDashIndexListedAfterTheIndexInAFile_doesNotHideIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist.1", "company.example",
				"entrystore.auth.signup.whitelist.1-", "ignored.example");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.1-]");
	}

	@Test
	void dottedBracketIndexListedBeforeTheIndexInAFile_doesNotHideIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist.[1]", "ignored.example",
				"entrystore.auth.signup.whitelist.1", "company.example");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.[1]]");
	}

	@Test
	void dottedBracketIndexListedAfterTheIndexInAFile_doesNotHideIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist.1", "company.example",
				"entrystore.auth.signup.whitelist.[1]", "ignored.example");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.[1]]");
	}

	@Test
	void yamlList_isRead() throws IOException {
		var environment = new MockEnvironment();
		new YamlPropertySourceLoader().load("application.yaml", new ByteArrayResource("""
				entrystore:
				  auth:
				    signup:
				      whitelist:
				        - a.example
				        - b.example
				""".getBytes(StandardCharsets.UTF_8))).forEach(environment.getPropertySources()::addLast);

		assertEquals(List.of("a.example", "b.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void exactKeyInSpringApplicationJson_winsOverAnAliasListedBeforeIt() {
		var environment = applicationJson(new MockEnvironment(), """
				{"entrystore.auth.signup.WHITE-LIST.1":"evil.example",\
				"entrystore.auth.signup.whitelist.1":"company.example"}""");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void exactKeyInSpringApplicationJson_winsOverAnAliasListedAfterIt() {
		var environment = applicationJson(new MockEnvironment(), """
				{"entrystore.auth.signup.whitelist.1":"company.example",\
				"entrystore.auth.signup.WHITE-LIST.1":"evil.example"}""");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void exactKeyOnTheCommandLine_winsOverAnAliasListedBeforeIt() {
		var environment = withHigher(new MockEnvironment(), ordered("commandLineArgs",
				"entrystore.auth.signup.WHITE-LIST.1", "evil.example",
				"entrystore.auth.signup.whitelist.1", "company.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void exactKeyOnTheCommandLine_winsOverAnAliasListedAfterIt() {
		var environment = withHigher(new MockEnvironment(), ordered("commandLineArgs",
				"entrystore.auth.signup.whitelist.1", "company.example",
				"entrystore.auth.signup.WHITE-LIST.1", "evil.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void dottedIndexInAFile_winsOverTheBracketedOneListedBeforeIt() {
		var environment = file(
				"entrystore.auth.signup.whitelist[1]", "bracketed.example",
				"entrystore.auth.signup.whitelist.1", "dotted.example");

		assertEquals(List.of("dotted.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist[1]]");
	}

	@Test
	void malformedCommandLineSpelling_fallsBackToAValidSpellingInTheSameSource() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "file.example"),
				ordered("commandLineArgs",
						"entrystore.auth.signup.whitelist.1-", "bad.example",
						"entrystore.auth.signup.WHITELIST.1", "cli.example"));

		assertEquals(List.of("cli.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.1-]");
	}

	@Test
	void doubleTrailingUnderscoreEnvironmentVariable_isReadLikeTheBinder() {
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1__", "company.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void prefixedDoubleTrailingUnderscoreEnvironmentVariable_isReadLikeTheBinder() {
		var environment = withHigher(new MockEnvironment(),
				variables("ACME_ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1__", "company.example"));
		applyEnvironmentPrefix(environment, "acme");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void indexedEntryInSpringApplicationJson_isReadLikeTheBinder() {
		var environment = applicationJson(file("entrystore.auth.signup.whitelist.1", "old.example"),
				"{\"entrystore.auth.signup.whitelist.1\":\"company.example\"}");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void bareValueInSpringApplicationJson_isOneElement() {
		var environment = applicationJson(new MockEnvironment(),
				"{\"entrystore.auth.signup.whitelist\":\"company.example\"}");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void leadingDashOverrideAbove_doesNotHideTheFileEntry() {
		// Boot resolves .1 to the higher .-1; the reader skips that malformed candidate, as 5.x never read it.
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "company.example"),
				commandLine("entrystore.auth.signup.whitelist.-1", "evil.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.-1]");
		assertEquals(List.of("evil.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"),
				"the binder takes the malformed override, which is why the reader does not follow it here");
	}

	@Test
	void trailingUnderscoreOverrideAbove_doesNotHideTheFileEntry() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "company.example"),
				commandLine("entrystore.auth.signup.whitelist.1_", "evil.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.1_]");
		assertEquals(Map.of("1", "evil.example", "1_", "evil.example"),
				Binder.get(environment).bind("entrystore.auth.signup.whitelist",
						Bindable.mapOf(String.class, String.class)).get(),
				"the binder takes the malformed override, which is why the reader does not follow it here");
	}

	@Test
	void dottedZeroAbove_doesNotHideTheZeroBasedListBelow() {
		var environment = withHigher(withHigher(new MockEnvironment(), ordered("lower",
						"entrystore.auth.signup.whitelist[0]", "a.example",
						"entrystore.auth.signup.whitelist[1]", "b.example")),
				commandLine("entrystore.auth.signup.whitelist.0", "evil.example"));

		assertEquals(List.of("a.example", "b.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.0]");
		assertEquals(List.of("evil.example", "b.example"),
				boundByBinder(environment, "entrystore.auth.signup.whitelist"),
				"the binder lets the dotted .0 replace the first element");
	}

	@Test
	void emptyElementBeforeTheIndex_isIgnored() {
		// Boot drops the empty element of ..1, but 5.x never read it, so 127.0.0.1 gets no SSRF exemption.
		var environment = file("entrystore.proxy.whitelist.local..1", "127.0.0.1");

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.whitelist.local"));
		assertWarned("ignored: [entrystore.proxy.whitelist.local..1]");
		assertEquals(List.of("127.0.0.1"), boundByBinder(environment, "entrystore.proxy.whitelist.local"),
				"the binder reads ..1 as index 1");
	}

	@Test
	void dotBeforeABracketedIndex_isIgnored() {
		var environment = file("entrystore.proxy.whitelist.local.[1]", "127.0.0.1");

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.whitelist.local"));
		assertWarned("ignored: [entrystore.proxy.whitelist.local.[1]]");
	}

	@Test
	void bareValueOnlyInANonEnumerableSource_isRead() {
		var environment = withHigher(new MockEnvironment(), new PropertySource<>("lookup") {
			@Override
			public Object getProperty(String name) {
				return "entrystore.auth.signup.whitelist".equals(name) ? "company.example" : null;
			}
		});

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void nonEnumerableSourceAbove_overridesTheEntryBelowLikeTheBinder() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "old.example"),
				new PropertySource<>("lookup") {
					@Override
					public Object getProperty(String name) {
						return "entrystore.auth.signup.whitelist.1".equals(name) ? "company.example" : null;
					}
				});

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void trailingUnderscoreIndexNextToTheIndex_isNamedAsWritten() {
		var environment = file(
				"entrystore.proxy.whitelist.anonymous.1", "a.example",
				"entrystore.proxy.whitelist.anonymous.1_", "b.example");

		assertEquals(List.of("a.example"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertWarned("ignored: [entrystore.proxy.whitelist.anonymous.1_");
	}

	@Test
	void canonicalEnvironmentVariable_winsOverItsTrailingUnderscoreAlias() {
		var environment = withHigher(new MockEnvironment(), variables(
				"ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1_", "underscored.example",
				"ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1", "plain.example"));

		assertEquals(List.of("plain.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("plain.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void doubleUnderscoreEnvironmentVariable_isReadLikeTheBinder() {
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE__AUTH_SIGNUP_WHITELIST_1", "company.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void lowercaseName_winsOverATrailingUnderscoreAliasListedBeforeIt() {
		var environment = withHigher(new MockEnvironment(), variables(
				"ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1_", "evil.example",
				"entrystore_auth_signup_whitelist_1", "company.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void lowercaseName_winsOverATrailingUnderscoreAliasListedAfterIt() {
		var environment = withHigher(new MockEnvironment(), variables(
				"entrystore_auth_signup_whitelist_1", "company.example",
				"ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1_", "evil.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("company.example"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void legacyDashedEnvironmentVariable_isNotRead_likeTheBinder() {
		// Boot maps the dashed spelling to remote.resource, which is not this key, so the binder ignores it too.
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE_PROXY_REMOTE_RESOURCE_DELETE_WHITELIST_1", "https://legacy.example"));

		assertEquals(List.of(), startAndRead(environment, "entrystore.proxy.remote-resource.delete.whitelist"));
		assertEquals(List.of(), boundByBinder(environment, "entrystore.proxy.remote-resource.delete.whitelist"));
	}

	@Test
	void trailingUnderscoreEnvironmentVariable_isTheBareValue() {
		// Boot binds KEY_ (no index) as the key itself.
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_", "x.example"));

		assertEquals(List.of("x.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void shadowedBareValue_stillHidesIndexedEntriesInItsOwnSource() {
		// The leftover .2 was hidden by the bare value in 5.x and must not come back with the command-line .1.
		var environment = withHigher(file(
						"entrystore.proxy.whitelist.local", "a.example",
						"entrystore.proxy.whitelist.local.2", "c.example"),
				commandLine("entrystore.proxy.whitelist.local.1", "b.example"));

		assertEquals(List.of("b.example"), startAndRead(environment, "entrystore.proxy.whitelist.local"));
		assertWarned("ignored: [entrystore.proxy.whitelist.local, entrystore.proxy.whitelist.local.2]");
	}

	@Test
	void orphanIndexAboveABareValue_doesNotEmptyTheList() {
		// .2 without .1 is never applied, so it must not displace the bare value: [] would admit every domain.
		var environment = withHigher(file("entrystore.auth.signup.whitelist", "company.example"),
				commandLine("entrystore.auth.signup.whitelist.2", "x.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.2]");
	}

	@Test
	void nullIndexedEntryAbove_doesNotReplaceTheEntryBelow() {
		var environment = applicationJson(file("entrystore.auth.password.blacklist.1", "blocked-user"),
				"{\"entrystore.auth.password.blacklist.1\":null}");

		assertEquals(List.of("blocked-user"), startAndRead(environment, "entrystore.auth.password.blacklist"));
		assertEquals(List.of("blocked-user"), boundByBinder(environment, "entrystore.auth.password.blacklist"));
	}

	@Test
	void nullBareValueAbove_doesNotReplaceTheEntriesBelow() {
		var environment = applicationJson(file("entrystore.auth.signup.whitelist.1", "company.example"),
				"{\"entrystore.auth.signup.whitelist\":null}");

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void unresolvablePlaceholder_isKeptLiterally() {
		var environment = file("entrystore.proxy.whitelist.local.1", "${undefined.host}");

		assertEquals(List.of("${undefined.host}"), startAndRead(environment, "entrystore.proxy.whitelist.local"));
	}

	@Test
	void shadowedBareValue_isNamedInTheWarning() {
		var environment = withHigher(file("entrystore.proxy.whitelist.anonymous", "file.example"),
				variables("ENTRYSTORE_PROXY_WHITELIST_ANONYMOUS", "guest.example"));

		assertEquals(List.of("guest.example"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertWarned("ignored: [entrystore.proxy.whitelist.anonymous]");
	}

	@Test
	void bareCustomPasswordRule_isOneRule() {
		var environment = file("entrystore.auth.password.rule.custom", "[0-9]");

		assertEquals(List.of("[0-9]"), startAndRead(environment, "entrystore.auth.password.rule.custom"));
		assertInformed("'entrystore.auth.password.rule.custom'", "entrystore.auth.password.rule.custom.1=[0-9]");
	}

	@Test
	void legacyConfig_readsTheSameListAsTheReader() {
		var environment = file(
				"entrystore.auth.signup.whitelist.1", "example.com",
				"entrystore.auth.signup.whitelist.3", "other.example");

		var config = new EntryStoreConfiguration(environment).createEntryStoreConfiguration();

		assertEquals(List.of("example.com"), config.getStringList("entrystore.auth.signup.whitelist"));
		assertEquals(List.of("example.com"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	// --- precedence across sources ---

	@Test
	void commandLineEntry_overridesABareValueInTheFile() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist", "file.example"),
				commandLine("entrystore.auth.signup.whitelist.1", "cli.example"));

		assertEquals(List.of("cli.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist]");
	}

	@Test
	void bracketedCommandLineEntry_keepsItsPrecedenceOverABareValueInTheFile() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist", "file.example"),
				commandLine("entrystore.auth.signup.whitelist[1]", "cli.example"));

		assertEquals(List.of("cli.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void environmentVariableEntry_overridesABareValueInTheFile() {
		var environment = withHigher(file("entrystore.proxy.whitelist.local", "file.example"),
				variables("ENTRYSTORE_PROXY_WHITELIST_LOCAL_1", "variable.example"));

		assertEquals(List.of("variable.example"), startAndRead(environment, "entrystore.proxy.whitelist.local"));
	}

	@Test
	void bareEnvironmentVariable_overridesIndexedEntriesInTheFile() {
		var environment = withHigher(file("entrystore.proxy.whitelist.anonymous.1", "file.example"),
				variables("ENTRYSTORE_PROXY_WHITELIST_ANONYMOUS", "guest.example"));

		assertEquals(List.of("guest.example"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
	}

	@Test
	void sameIndexInTwoSources_takesTheHigherPrecedenceValue() {
		var environment = withHigher(file(
						"entrystore.proxy.whitelist.local.1", "file.example",
						"entrystore.proxy.whitelist.local.3", "ignored.example"),
				variables("ENTRYSTORE_PROXY_WHITELIST_LOCAL_1_", "variable.example"));

		assertEquals(List.of("variable.example"), startAndRead(environment, "entrystore.proxy.whitelist.local"));
	}

	// --- Spring-native forms bind as Spring binds them ---

	@Test
	void underscoreIndexedEnvironmentVariable_restrictsTheSignupWhitelist() {
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1_", "example.com"));

		assertEquals(List.of("example.com"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of("example.com"), boundByBinder(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void environmentVariablesWithAGap_endTheListAtTheGapWithAWarning() {
		var environment = withHigher(new MockEnvironment(), variables(
				"ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_1", "https://one.example",
				"ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_3", "https://three.example"));

		assertEquals(List.of("https://one.example"),
				startAndRead(environment, "entrystore.proxy.remote-resource.delete.whitelist"));
		assertWarned("ignored: [", "ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_3");
	}

	@Test
	void zeroBasedBracketedList_keepsEveryEntryInOrder() {
		// A YAML sequence flattens to [0], [1], ...; its index 0 is the first element.
		var environment = file(
				"entrystore.auth.password.whitelist[0]", "admin",
				"entrystore.auth.password.whitelist[1]", "user@test.com",
				"entrystore.auth.password.whitelist[2]", "third@test.com");

		assertEquals(List.of("admin", "user@test.com", "third@test.com"),
				startAndRead(environment, "entrystore.auth.password.whitelist"));
		assertEquals(List.of("admin", "user@test.com", "third@test.com"),
				boundByBinder(environment, "entrystore.auth.password.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void bracketedIndexZeroOnTheCommandLine_precedesTheDottedEntriesOfTheFile() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "a.example"),
				commandLine("entrystore.auth.signup.whitelist[0]", "z.example"));

		assertEquals(List.of("z.example", "a.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void environmentVariableIndexZero_precedesTheDottedEntriesOfTheFile() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "a.example"),
				variables("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_0", "z.example"));

		assertEquals(List.of("z.example", "a.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
	}

	@Test
	void bracketedEntryAfterAGap_isIgnoredWithAWarning() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.1", "a.example"),
				commandLine("entrystore.auth.signup.whitelist[3]", "c.example"));

		assertEquals(List.of("a.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist[3]]");
	}

	@Test
	void bracketedIndexZero_isAppliedWhenTheFileHoldsOnlyAnIgnoredEntry() {
		var environment = withHigher(file("entrystore.auth.signup.whitelist.l", "ignored.example"),
				commandLine("entrystore.auth.signup.whitelist[0]", "company.example"));

		assertEquals(List.of("company.example"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.l]");
	}

	@Test
	void nonNumericBracketedName_isIgnoredWithAWarning() {
		var environment = file("entrystore.auth.signup.whitelist[evil]", "evil.example");

		assertEquals(List.of(), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist[evil]]");
	}

	@Test
	void nonNumericEnvironmentVariable_isIgnoredWithAWarning() {
		var environment = withHigher(new MockEnvironment(),
				variables("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_EVIL", "other.example"));

		assertEquals(List.of(), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ENTRYSTORE_AUTH_SIGNUP_WHITELIST_EVIL");
	}

	@Test
	void prefixedEnvironmentVariables_stillBind() {
		var environment = withHigher(new MockEnvironment(),
				variables("ACME_ENTRYSTORE_AUTH_SIGNUP_WHITELIST_1_", "example.com",
						"ACME_ENTRYSTORE_PROXY_MAXREDIRECTS", "5"));
		applyEnvironmentPrefix(environment, "acme");

		assertEquals(List.of("example.com"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertEquals("5", environment.getProperty("entrystore.proxy.max-redirects"));
	}

	// --- general ---

	@Test
	void placeholderReferencingAnotherList_resolves() {
		var environment = file(
				"entrystore.proxy.whitelist.local", "localhost",
				"entrystore.proxy.whitelist.anonymous", "${entrystore.proxy.whitelist.local}");

		assertEquals(List.of("localhost"), startAndRead(environment, "entrystore.proxy.whitelist.anonymous"));
		assertEquals(List.of("localhost"), IndexedListSettings.read(environment, "entrystore.proxy.whitelist.local"));
	}

	@Test
	void startup_leavesEveryPropertySourceAsItIs() {
		var environment = withHigher(file("entrystore.proxy.whitelist.local", "localhost"),
				variables("ENTRYSTORE_PROXY_WHITELIST_ANONYMOUS_3", "guest.example"));
		ConfigurationPropertySources.attach(environment);
		List<PropertySource<?>> before = environment.getPropertySources().stream().toList();

		start(environment);

		assertEquals(before, environment.getPropertySources().stream().toList());
		assertTrue(before.stream()
				.allMatch(source -> environment.getPropertySources().get(source.getName()) == source));
	}

	@Test
	void contiguousIndexedList_isReadWithoutAWarning() {
		var environment = file(
				"entrystore.auth.password.whitelist.1", "admin",
				"entrystore.auth.password.whitelist.2", "user@test.com");

		assertEquals(List.of("admin", "user@test.com"),
				startAndRead(environment, "entrystore.auth.password.whitelist"));
		assertEquals(List.of("admin", "user@test.com"),
				boundByBinder(environment, "entrystore.auth.password.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void absentSetting_isAnEmptyList() {
		assertEquals(List.of(), startAndRead(new MockEnvironment(), "entrystore.auth.signup.whitelist"));
		assertEquals(List.of(), warnings);
	}

	@Test
	void nonAsciiDigitSuffixInAFile_isIgnoredWithAWarning() {
		// adapt() cannot represent ٢, so the binder never binds it, and 5.x never read it either.
		var environment = file(
				"entrystore.auth.signup.whitelist.٢", "evil.example",
				"entrystore.auth.signup.whitelist.1", "example.com");

		assertEquals(List.of("example.com"), startAndRead(environment, "entrystore.auth.signup.whitelist"));
		assertWarned("ignored: [entrystore.auth.signup.whitelist.٢]");
	}

	@Test
	void runsAfterConfigData() {
		// After ConfigDataEnvironmentPostProcessor, so entrystore.properties is loaded when we scan.
		assertEquals(Ordered.LOWEST_PRECEDENCE - 1,
				new IndexedListSettings(_ -> new RecordingLog(warnings)).getOrder());
	}

	@Test
	void isRegisteredAsAnEnvironmentPostProcessor() {
		var processors = RegisteredEnvironmentPostProcessors.load();

		assertTrue(processors.stream().anyMatch(IndexedListSettings.class::isInstance),
				"IndexedListSettings must be registered in META-INF/spring.factories; got: "
						+ processors.stream().map(processor -> processor.getClass().getName()).toList());
	}

	/** Runs the startup post-processor, then reads the list the way consumers do. */
	private List<String> startAndRead(ConfigurableEnvironment environment, String key) {
		start(environment);
		return IndexedListSettings.read(environment, key);
	}

	private void start(ConfigurableEnvironment environment) {
		ConfigurationPropertySources.attach(environment);
		new IndexedListSettings(_ -> new RecordingLog(warnings, infos)).postProcessEnvironment(environment, null);
	}

	/** Asserts that one WARN contains every fragment. */
	private void assertWarned(String... fragments) {
		assertTrue(warnings.stream().anyMatch(warning -> Arrays.stream(fragments).allMatch(warning::contains)),
				String.valueOf(warnings));
	}

	/** Asserts that one INFO contains every fragment. */
	private void assertInformed(String... fragments) {
		assertTrue(infos.stream().anyMatch(info -> Arrays.stream(fragments).allMatch(info::contains)),
				String.valueOf(infos));
	}

	/**
	 * An environment whose lowest-precedence source is entrystore.properties with these lines, in this order,
	 * loaded by Boot's own properties loader as config data loads it.
	 */
	private static MockEnvironment file(String... keysAndValues) {
		var lines = new StringBuilder();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			lines.append(keysAndValues[i]).append('=').append(keysAndValues[i + 1].replace("\\", "\\\\")).append('\n');
		}
		var environment = new MockEnvironment();
		try {
			var resource = new ByteArrayResource(lines.toString().getBytes(StandardCharsets.UTF_8));
			new PropertiesPropertySourceLoader()
					.load("entrystore.properties", resource, StandardCharsets.UTF_8)
					.forEach(environment.getPropertySources()::addLast);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return environment;
	}

	private static MockEnvironment withHigher(MockEnvironment environment, PropertySource<?> source) {
		environment.getPropertySources().addFirst(source);
		return environment;
	}

	private static PropertySource<?> commandLine(String key, String value) {
		return new MapPropertySource("commandLineArgs", Map.of(key, value));
	}

	/** What Boot's binder binds for {@code key} as a map with numeric keys, ordered by index. */
	private static List<String> boundByBinder(ConfigurableEnvironment environment, String key) {
		return Binder.get(environment).bind(key, Bindable.mapOf(String.class, String.class))
				.map(bound -> bound.entrySet().stream()
						.sorted(Map.Entry.comparingByKey(Comparator.comparing(Integer::valueOf)))
						.map(Map.Entry::getValue)
						.toList())
				.orElse(List.of());
	}

	/** A source that enumerates its names in the given order, as a properties file does its lines. */
	private static PropertySource<?> ordered(String name, String... keysAndValues) {
		var properties = new LinkedHashMap<String, Object>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			properties.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return new MapPropertySource(name, properties);
	}

	/** {@code environment} with {@code json} applied as SPRING_APPLICATION_JSON by Boot's own post-processor. */
	private static MockEnvironment applicationJson(MockEnvironment environment, String json) {
		environment.setProperty(SpringApplicationJsonEnvironmentPostProcessor.SPRING_APPLICATION_JSON_PROPERTY, json);
		new SpringApplicationJsonEnvironmentPostProcessor()
				.postProcessEnvironment(environment, new SpringApplication());
		return environment;
	}

	/** Wraps the environment variables in Boot's prefix-aware source, as {@code setEnvironmentPrefix} does. */
	private static void applyEnvironmentPrefix(MockEnvironment environment, String prefix) {
		var application = new SpringApplication();
		application.setEnvironmentPrefix(prefix);
		new SystemEnvironmentPropertySourceEnvironmentPostProcessor().postProcessEnvironment(environment, application);
	}

	private static PropertySource<?> variables(String... namesAndValues) {
		var variables = new LinkedHashMap<String, Object>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			variables.put(namesAndValues[i], namesAndValues[i + 1]);
		}
		return new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				variables);
	}
}
