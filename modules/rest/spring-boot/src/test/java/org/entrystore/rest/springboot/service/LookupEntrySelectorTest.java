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

package org.entrystore.rest.springboot.service;

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the selector against a real in-memory repository, so that ownership and access are the core's own. */
class LookupEntrySelectorTest {

	private static final String BASE_URL = "http://localhost:8181/";
	private static final URI EXTERNAL = URI.create("https://example.org/shared");

	private RepositoryManagerImpl rm;
	private PrincipalManager pm;
	private Context context;
	private URI previousUser;

	@BeforeEach
	void setUp() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, BASE_URL);
		config.setProperty(Settings.SOLR, "off");
		rm = new RepositoryManagerImpl(BASE_URL, config);
		pm = rm.getPrincipalManager();
		previousUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		context = (Context) rm.getContextManager().createResource(null, GraphType.Context, null, null).getResource();
	}

	@AfterEach
	void tearDown() {
		// The authenticated user is a static thread-local and would leak into later tests
		pm.setAuthenticatedUserURI(previousUser);
		rm.shutdown();
	}

	@Test
	void ownerIsPickedOverALinkWithALowerEntryUri() {
		Entry owner = context.createResource("z-owner", GraphType.None, null, null);
		Entry link = context.createLink("a-link", owner.getResourceURI(), null);

		Optional<Entry> picked = LookupEntrySelector.select(Set.of(owner, link), owner.getResourceURI());

		assertThat(picked).contains(owner);
	}

	@Test
	void readableLinkIsPickedOverAnUnreadableOwner() {
		Entry owner = context.createResource("a-owner", GraphType.None, null, null);
		Entry link = context.createLink("z-link", owner.getResourceURI(), null);
		link.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());

		Optional<Entry> picked = asGuest(() -> LookupEntrySelector.select(Set.of(owner, link), owner.getResourceURI()));

		assertThat(picked).contains(link);
	}

	@Test
	void lowestEntryUriIsPickedAmongLinksToAnExternalUri() {
		Entry c = context.createLink("c", EXTERNAL, null);
		Entry a = context.createLink("a", EXTERNAL, null);
		Entry b = context.createLink("b", EXTERNAL, null);

		Optional<Entry> picked = LookupEntrySelector.select(List.of(c, a, b), EXTERNAL);

		assertThat(picked).contains(a);
	}

	@Test
	void ownerIsPickedWhenTheCallerMayReadNoHolder() {
		Entry owner = context.createResource("z-owner", GraphType.None, null, null);
		Entry link = context.createLink("a-link", owner.getResourceURI(), null);

		Optional<Entry> picked = asGuest(() -> LookupEntrySelector.select(Set.of(owner, link), owner.getResourceURI()));

		assertThat(picked).contains(owner);
	}

	@Test
	void localEntryRenamedToAnExternalUriDoesNotOwnIt() {
		Entry renamed = context.createResource("z-renamed", GraphType.None, null, null);
		renamed.setResourceURI(EXTERNAL);
		Entry link = context.createLink("a-link", EXTERNAL, null);

		Optional<Entry> picked = LookupEntrySelector.select(Set.of(renamed, link), EXTERNAL);

		assertThat(picked).contains(link);
	}

	@Test
	void noHolderGivesEmpty() {
		assertThat(LookupEntrySelector.select(Set.of(), EXTERNAL)).isEmpty();
	}

	private <T> T asGuest(Supplier<T> action) {
		URI previous = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		try {
			return action.get();
		} finally {
			pm.setAuthenticatedUserURI(previous);
		}
	}

}
