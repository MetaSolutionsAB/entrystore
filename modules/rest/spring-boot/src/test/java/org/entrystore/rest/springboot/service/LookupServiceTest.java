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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.config.Config;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.model.api.LookupScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the context lookup against a real in-memory repository, where a referenced entry can be made corrupt. */
class LookupServiceTest {

	private static final String BASE_URL = "http://localhost:8181/";
	private static final URI EXTERNAL = URI.create("https://example.org/shared");

	private RepositoryManagerImpl rm;
	private PrincipalManager pm;
	private Context context;
	private URI previousUser;
	private LookupService service;

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
		service = new LookupService(rm, new ContextService(rm, new ReservedNamesService(), pm));
	}

	@AfterEach
	void tearDown() {
		// The authenticated user is a static thread-local and would leak into later tests
		pm.setAuthenticatedUserURI(previousUser);
		rm.shutdown();
	}

	@Test
	void readableReferenceWhoseReferencedEntryIsCorruptIsAnsweredWithAnEmptyExternalGraph() {
		Entry target = context.createResource("target", GraphType.None, null, null);
		Entry reference = context.createReference("ref", EXTERNAL, target.getLocalMetadataURI(), null);
		reference.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
		corrupt(target);
		String contextId = context.getEntry().getId();

		Entry answered = asGuest(() -> service.lookupInContext(contextId, EXTERNAL));
		String external = asGuest(
				() -> service.getMetadataByScope(answered, LookupScope.EXTERNAL, "application/n-triples"));

		assertThat(answered).isEqualTo(reference);
		assertThat(external).isBlank();
	}

	@Test
	void referenceIsCheckedBeforeItsReferencedEntry() {
		Entry group = pm.createResource(null, GraphType.Group, null, null);
		Entry reference = context.createReference("ref", EXTERNAL, group.getLocalMetadataURI(), null);
		reference.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getAdminUser().getURI());
		corrupt(group);
		String contextId = context.getEntry().getId();

		assertThatThrownBy(() -> asGuest(() -> service.lookupInContext(contextId, EXTERNAL)))
				.isNotInstanceOf(AuthorizationException.class);
	}

	/** Makes the entry unloadable: removes its resource statement from the store and evicts it from the cache. */
	private void corrupt(Entry entry) {
		IRI entryGraph = SimpleValueFactory.getInstance().createIRI(entry.getEntryURI().toString());
		try (RepositoryConnection rc = rm.getRepository().getConnection()) {
			rc.remove((Resource) null, RepositoryProperties.resource, null, entryGraph);
		}
		((ContextImpl) entry.getContext()).getSoftCache().remove(entry);
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
