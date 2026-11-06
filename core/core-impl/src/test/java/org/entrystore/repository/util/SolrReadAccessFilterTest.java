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

package org.entrystore.repository.util;

import org.apache.solr.client.solrj.request.SolrQuery;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.repository.RepositoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SolrReadAccessFilterTest {

	private static final String BASE = "https://example.org/store";
	private static final URI USER = URI.create(BASE + "/_principals/resource/7");
	private static final URI GUEST = URI.create(BASE + "/_principals/resource/_guest");
	private static final URI USERS = URI.create(BASE + "/_principals/resource/_users");
	private static final URI GROUP = URI.create(BASE + "/_principals/resource/12");
	private static final URI CONTEXTS = URI.create(BASE + "/_contexts");

	private static final Pattern PARAM_REFERENCE = Pattern.compile("\\$([\\w.]+)");

	private static SolrReadAccessFilter filterFor(URI... principals) {
		return new SolrReadAccessFilter(USER, Stream.of(principals).map(URI::toString).toList(),
				CONTEXTS.toString());
	}

	/** A repository with authorization checks on whose thread user is {@code user}, holding the given principals. */
	private static RepositoryManager repositoryWith(URI user, boolean adminOrAdminGroup, Set<URI> principals) {
		RepositoryManager rm = mock(RepositoryManager.class);
		PrincipalManager pm = mock(PrincipalManager.class);
		ContextManager cm = mock(ContextManager.class);
		Entry contextsEntry = mock(Entry.class);
		User guest = mock(User.class);
		when(rm.isCheckForAuthorization()).thenReturn(true);
		when(rm.getPrincipalManager()).thenReturn(pm);
		when(rm.getContextManager()).thenReturn(cm);
		when(cm.getEntry()).thenReturn(contextsEntry);
		when(contextsEntry.getResourceURI()).thenReturn(CONTEXTS);
		when(pm.getGuestUser()).thenReturn(guest);
		when(guest.getURI()).thenReturn(GUEST);
		when(pm.getAuthenticatedUserURI()).thenReturn(user);
		if (user != null) {
			when(pm.isUserAdminOrAdminGroup(user)).thenReturn(adminOrAdminGroup);
		}
		when(pm.getAclPrincipalsOfAuthenticatedUser()).thenReturn(principals);
		return rm;
	}

	@Test
	void applyToAddsOneFilterQueryAndKeepsTheClientsFilterQueries() {
		SolrQuery query = new SolrQuery("*:*");
		query.addFilterQuery("context:\"" + BASE + "/1\"");

		filterFor(USER, GUEST).applyTo(query);

		String[] filterQueries = query.getFilterQueries();
		assertEquals(2, filterQueries.length);
		assertEquals("context:\"" + BASE + "/1\"", filterQueries[0]);
	}

	@Test
	void everyParameterTheFilterDereferencesIsSet() {
		SolrQuery query = new SolrQuery("*:*");

		filterFor(USER, GUEST, USERS).applyTo(query);

		Set<String> dereferenced = dereferencedParameters(query);
		assertTrue(dereferenced.containsAll(Set.of("esacl.principals", "esacl.user", "esacl.contexts")),
				dereferenced.toString());
	}

	/**
	 * The parameters the filter query dereferences, directly or through other parameters. Solr answers 400 for a
	 * $name it cannot resolve, so this fails on every reference to a parameter that is not set.
	 */
	private static Set<String> dereferencedParameters(SolrQuery query) {
		List<String> pending = new ArrayList<>(List.of(query.getFilterQueries()[0]));
		Set<String> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			Matcher references = PARAM_REFERENCE.matcher(pending.removeLast());
			while (references.find()) {
				String name = references.group(1);
				String value = query.get(name);
				assertNotNull(value, "dereferenced parameter " + name + " is not set");
				if (visited.add(name)) {
					pending.add(value);
				}
			}
		}
		return visited;
	}

	@Test
	void principalsAreOneSpaceSeparatedParameterSoACommaInAUriKeepsItWhole() {
		URI withComma = URI.create(BASE + "/_principals/resource/a,b");
		SolrQuery query = new SolrQuery("*:*");

		filterFor(withComma, GUEST).applyTo(query);

		assertArrayEquals(new String[]{GUEST.toString(), withComma.toString()},
				query.get("esacl.principals").split(" "));
		assertTrue(query.get("esacl.admin").contains("separator=' '"), query.get("esacl.admin"));
	}

	@Test
	void withoutPrincipalsOnlyTheCallersOwnEntryIsReadableAndNoLocalReference() {
		SolrQuery query = new SolrQuery("*:*");

		filterFor().applyTo(query);

		assertArrayEquals(new String[]{"{!bool must=$esacl.self must_not=$esacl.localReference}"},
				query.getFilterQueries());
		assertEquals(Set.of("esacl.self", "esacl.user", "esacl.localReference"), dereferencedParameters(query));
		assertEquals("{!term f=resource v=$esacl.user}", query.get("esacl.self"));
		// a reference's document holds its target's metadata, which the caller's own-entry rule does not cover
		assertEquals("referencedEntry:*", query.get("esacl.localReference"));
		assertEquals(USER.toString(), query.get("esacl.user"));
		assertNull(query.get("esacl.principals"));
	}

	@ParameterizedTest(name = "facet.mincount {0} becomes {1}")
	@CsvSource(value = {"absent, 1", "-1, 1", "0, 1", "1, 1", "5, 5"}, nullValues = "absent")
	void facetMinCountBelowOneIsRaisedToOne(Integer requested, int expected) {
		SolrQuery query = new SolrQuery("*:*");
		query.setFacet(true);
		query.addFacetField("uri");
		if (requested != null) {
			query.set("facet.mincount", requested);
		}

		filterFor(GUEST).applyTo(query);

		assertEquals(expected, query.getInt("facet.mincount"));
	}

	@Test
	void perFieldFacetMinCountBelowOneIsRaisedToOne() {
		SolrQuery query = new SolrQuery("*:*");
		query.setFacet(true);
		query.addFacetField("uri", "title");
		query.set("f.uri.facet.mincount", 0);
		query.set("f.title.facet.mincount", 3);

		filterFor(GUEST).applyTo(query);

		assertEquals(1, query.getInt("f.uri.facet.mincount"));
		assertEquals(3, query.getInt("f.title.facet.mincount"));
	}

	@Test
	void aQueryWithoutFacetsGetsNoFacetParameters() {
		SolrQuery query = new SolrQuery("*:*");

		filterFor(GUEST).applyTo(query);

		assertNull(query.get("facet.mincount"));
	}

	@Test
	void noFilterWhenAuthorizationChecksAreOff() {
		RepositoryManager rm = repositoryWith(USER, false, Set.of(USER, GUEST));
		when(rm.isCheckForAuthorization()).thenReturn(false);

		assertEquals(Optional.empty(), SolrReadAccessFilter.forAuthenticatedUser(rm));
	}

	@Test
	void noFilterForTheAdminOrAnAdminGroupMember() {
		RepositoryManager rm = repositoryWith(USER, true, Set.of(USER, GUEST));

		assertEquals(Optional.empty(), SolrReadAccessFilter.forAuthenticatedUser(rm));
	}

	@Test
	void aUsersFilterHoldsItsPrincipalsAndTheContextsResource() {
		RepositoryManager rm = repositoryWith(USER, false, Set.of(USER, GROUP, USERS, GUEST));

		SolrReadAccessFilter filter = SolrReadAccessFilter.forAuthenticatedUser(rm).orElseThrow();

		assertEquals(USER, filter.user());
		// sorted, so that one caller always sends the same request and hits the same filterCache entry
		assertEquals(List.of(GROUP.toString(), USER.toString(), GUEST.toString(), USERS.toString()),
				filter.principals());
		assertEquals(CONTEXTS.toString(), filter.contextsResource());
	}

	@Test
	void aThreadWithoutUserIsFilteredAsTheGuest() {
		RepositoryManager rm = repositoryWith(null, false, Set.of(GUEST));

		SolrReadAccessFilter filter = SolrReadAccessFilter.forAuthenticatedUser(rm).orElseThrow();

		assertEquals(GUEST, filter.user());
	}
}
