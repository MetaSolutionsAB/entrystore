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

package org.entrystore.impl;

import org.apache.solr.client.solrj.SolrClient;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.SolrSearchIndex;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class RepositoryManagerImplTest {

	@Disabled("To be implemented")
	@Test
	public void testGetInstance() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetPublicRepository() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testExportToFile() throws Exception {
		// TODO
	}

	@Test
	public void shutdownContinuesWhenSolrIndexShutdownThrows() throws Exception {
		SolrSearchIndex throwingSolrIndex = mock(SolrSearchIndex.class);
		doThrow(new RuntimeException("simulated Solr index shutdown failure")).when(throwingSolrIndex).shutdown();

		assertShutdownCompletesAfterInjecting("solrIndex", throwingSolrIndex);

		verify(throwingSolrIndex).shutdown();
	}

	@Test
	public void shutdownContinuesWhenPublicRepositoryShutdownThrows() throws Exception {
		PublicRepository throwingPublicRepository = mock(PublicRepository.class);
		doThrow(new RuntimeException("simulated public repository shutdown failure")).when(throwingPublicRepository).shutdown();

		assertShutdownCompletesAfterInjecting("publicRepository", throwingPublicRepository);

		verify(throwingPublicRepository).shutdown();
	}

	@Test
	public void shutdownContinuesWhenSolrServerCloseThrows() throws Exception {
		SolrClient throwingSolrServer = mock(SolrClient.class);
		doThrow(new RuntimeException("simulated Solr server close failure")).when(throwingSolrServer).close();

		assertShutdownCompletesAfterInjecting("solrServer", throwingSolrServer);

		verify(throwingSolrServer).close();
	}

	// Builds a minimal RepositoryManagerImpl, injects a collaborator whose shutdown throws into the named
	// field, then asserts shutdown() swallows the failure and still reaches its final step. Deregisters the
	// instance from the static instances map afterwards so it cannot be served stale to a later same-URL test.
	private void assertShutdownCompletesAfterInjecting(String fieldName, Object throwingCollaborator) throws Exception {
		String baseUrl = "http://localhost:8181/";
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, baseUrl);
		config.setProperty(Settings.SOLR, "off");
		RepositoryManagerImpl rm = new RepositoryManagerImpl(baseUrl, config);
		try {
			setField(rm, fieldName, throwingCollaborator);

			assertDoesNotThrow(rm::shutdown, "shutdown() must swallow a subsystem failure and continue");

			assertTrue(getBooleanField(rm, "shutdown"),
					"shutdown flag must be set, proving the steps after the failing one still ran");
		} finally {
			deregisterInstance(baseUrl);
		}
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = RepositoryManagerImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static boolean getBooleanField(Object target, String name) throws Exception {
		Field field = RepositoryManagerImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getBoolean(target);
	}

	@SuppressWarnings("unchecked")
	private static void deregisterInstance(String baseUrl) throws Exception {
		Field field = RepositoryManagerImpl.class.getDeclaredField("instances");
		field.setAccessible(true);
		((Map<String, ?>) field.get(null)).remove(baseUrl);
	}

	@Disabled("To be implemented")
	@Test
	public void testGetContextManager() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetPrincipalManager() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetRepositoryURL() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testIsCheckForAuthorization() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSetCheckForAuthorization() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetSystemContextAliases() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetSystemContextClassForAlias() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetRegularContextClass() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testHasModificationLockOut() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSetModificationLockOut() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetConfiguration() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetSoftCache() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetDefaultQuota() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testHasQuotas() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testFireRepositoryEvent() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRegisterListener() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testUnregisterListener() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetIndex() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetValueFactory() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetNamedGraphCount() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTripleCount() throws Exception {
		// TODO
	}
}
