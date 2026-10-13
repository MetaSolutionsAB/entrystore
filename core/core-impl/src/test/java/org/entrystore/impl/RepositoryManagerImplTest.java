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
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.Rio;
import org.entrystore.Entry;
import org.entrystore.SearchIndex.ReindexResult;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.SolrSearchIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class RepositoryManagerImplTest {

	@Test
	public void reindexIsCompleteWhenQueueDrainsDespiteContextsAndEntriesThatCouldNotBeIndexed() {
		assertTrue(RepositoryManagerImpl.isReindexComplete(new ReindexResult(2, 5, false), () -> true));
	}

	@Test
	public void reindexIsIncompleteWhenQueueDoesNotDrain() {
		assertFalse(RepositoryManagerImpl.isReindexComplete(new ReindexResult(0, 0, false), () -> false));
	}

	@Test
	public void interruptedReindexIsIncompleteWithoutWaitingForTheQueue() {
		AtomicBoolean waitedForQueue = new AtomicBoolean();

		boolean complete = RepositoryManagerImpl.isReindexComplete(new ReindexResult(0, 0, true), () -> {
			waitedForQueue.set(true);
			return true;
		});

		assertFalse(complete);
		assertFalse(waitedForQueue.get(),
				"Waiting for the queue has no timeout and must be skipped after an interruption");
	}

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

	/** The export only reads the repository, so the tests share one repository manager. */
	@Nested
	@TestInstance(TestInstance.Lifecycle.PER_CLASS)
	class ExportToFile {

		private static final String BASE_URL = "http://localhost:8181/";

		private RepositoryManagerImpl rm;

		@BeforeAll
		void startRepositoryManager() {
			rm = newMinimalRepositoryManager(BASE_URL);
		}

		@AfterAll
		void shutDownRepositoryManager() throws Exception {
			rm.shutdown();
			deregisterInstance(BASE_URL);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("exportFormats")
		public void writesADumpThatParsesBackToTheRepositoryStatements(RDFFormat format, @TempDir Path tempDir)
				throws Exception {
			Path dump = tempDir.resolve("repository." + format.getDefaultFileExtension() + ".gz");

			rm.exportToFile(rm.getRepository(), dump.toUri(), true, format);

			long statementsInRepository;
			try (RepositoryConnection con = rm.getRepository().getConnection()) {
				statementsInRepository = con.size();
			}
			assertTrue(statementsInRepository > 0, "an empty repository would make the round trip prove nothing");
			try (InputStream in = new GZIPInputStream(Files.newInputStream(dump))) {
				assertEquals(statementsInRepository, Rio.parse(in, "", format).size());
			}
		}

		Stream<RDFFormat> exportFormats() {
			return RepositoryManagerImpl.EXPORT_FORMATS.stream();
		}

		@Test
		public void throwsAndKeepsATargetItCannotOpen(@TempDir Path tempDir) throws Exception {
			Path emptyDirectory = Files.createDirectory(tempDir.resolve("repository.xml"));

			assertThrows(IOException.class,
					() -> rm.exportToFile(rm.getRepository(), emptyDirectory.toUri(), false, RDFFormat.TRIX));

			assertTrue(Files.isDirectory(emptyDirectory), "a target the export did not open must not be deleted");
		}

		@Test
		public void deletesThePartialDumpWhenTheExportFails(@TempDir Path tempDir) {
			Path dump = tempDir.resolve("repository.xml.gz");
			Repository failingRepository = AbstractCoreTest.repositoryFailingMidExport();

			assertThrows(RDFHandlerException.class,
					() -> rm.exportToFile(failingRepository, dump.toUri(), true, RDFFormat.TRIX));

			assertFalse(Files.exists(dump), "a partly written dump must not be left behind");
		}

		@Test
		public void deletesThePartialDumpWhenTheExportFailsWithAnError(@TempDir Path tempDir) {
			Path dump = tempDir.resolve("repository.xml.gz");
			Repository failingRepository =
					AbstractCoreTest.repositoryFailingMidExport(new OutOfMemoryError("simulated"));

			assertThrows(OutOfMemoryError.class,
					() -> rm.exportToFile(failingRepository, dump.toUri(), true, RDFFormat.TRIX));

			assertFalse(Files.exists(dump), "a partly written dump must not be left behind");
		}

		@Test
		public void rejectsAFormatWithoutNamedGraphs(@TempDir Path tempDir) {
			Path dump = tempDir.resolve("repository.ttl");

			assertThrows(IllegalArgumentException.class,
					() -> rm.exportToFile(rm.getRepository(), dump.toUri(), false, RDFFormat.TURTLE));

			assertFalse(Files.exists(dump));
		}

		@Test
		public void rejectsANullFormat(@TempDir Path tempDir) {
			Path dump = tempDir.resolve("repository");

			assertThrows(IllegalArgumentException.class,
					() -> rm.exportToFile(rm.getRepository(), dump.toUri(), false, null));

			assertFalse(Files.exists(dump));
		}
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
	public void shutdownContinuesWhenSolrServerCloseThrowsRuntimeException() throws Exception {
		SolrClient throwingSolrServer = mock(SolrClient.class);
		doThrow(new RuntimeException("simulated Solr server close failure")).when(throwingSolrServer).close();

		assertShutdownCompletesAfterInjecting("solrServer", throwingSolrServer);

		verify(throwingSolrServer).close();
	}

	@Test
	public void shutdownContinuesWhenSolrServerCloseThrowsCheckedException() throws Exception {
		SolrClient throwingSolrServer = mock(SolrClient.class);
		doThrow(new IOException("simulated Solr server close failure")).when(throwingSolrServer).close();

		assertShutdownCompletesAfterInjecting("solrServer", throwingSolrServer);

		verify(throwingSolrServer).close();
	}

	@Test
	public void fireRepositoryEvent_runsRemainingListenersWhenOneThrows() throws Exception {
		String baseUrl = "http://localhost:8181/";
		RepositoryManagerImpl rm = newMinimalRepositoryManager(baseUrl);
		try {
			List<String> reached = new ArrayList<>();
			// Listener order is unspecified, so whichever runs first is the one that throws.
			rm.registerListener(recordingListener("first", reached), RepositoryEvent.ResourceUpdated);
			rm.registerListener(recordingListener("second", reached), RepositoryEvent.ResourceUpdated);
			rm.registerListener(recordingListener("all", reached), RepositoryEvent.All);

			assertDoesNotThrow(() -> rm.fireRepositoryEvent(
					new RepositoryEventObject(mock(Entry.class), RepositoryEvent.ResourceUpdated)));

			assertEquals(Set.of("first", "second", "all"), Set.copyOf(reached),
					"a listener that throws must not keep the remaining listeners, including All, from running");
		} finally {
			deregisterInstance(baseUrl);
		}
	}

	@Test
	public void fireRepositoryEvent_propagatesAnError() throws Exception {
		String baseUrl = "http://localhost:8181/";
		RepositoryManagerImpl rm = newMinimalRepositoryManager(baseUrl);
		try {
			rm.registerListener(new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					throw new AssertionError("simulated listener error");
				}
			}, RepositoryEvent.ResourceUpdated);

			assertThrows(AssertionError.class, () -> rm.fireRepositoryEvent(
					new RepositoryEventObject(mock(Entry.class), RepositoryEvent.ResourceUpdated)));
		} finally {
			deregisterInstance(baseUrl);
		}
	}

	private static RepositoryListener recordingListener(String name, List<String> reached) {
		return new RepositoryListener() {
			@Override
			public void repositoryUpdated(RepositoryEventObject eventObject) {
				boolean first = reached.isEmpty();
				reached.add(name);
				if (first) {
					throw new IllegalStateException("simulated failure in listener " + name);
				}
			}
		};
	}

	private static RepositoryManagerImpl newMinimalRepositoryManager(String baseUrl) {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, baseUrl);
		config.setProperty(Settings.SOLR, "off");
		return new RepositoryManagerImpl(baseUrl, config);
	}

	// Builds a minimal RepositoryManagerImpl, injects a collaborator whose shutdown throws into the named
	// field, then asserts shutdown() swallows the failure and still reaches its final step. Deregisters the
	// instance from the static instances map afterwards so it cannot be served stale to a later same-URL test.
	private void assertShutdownCompletesAfterInjecting(String fieldName, Object throwingCollaborator) throws Exception {
		String baseUrl = "http://localhost:8181/";
		RepositoryManagerImpl rm = newMinimalRepositoryManager(baseUrl);
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

	@Test
	public void maximumFileSizeDefaultsToOneGigabyte() throws Exception {
		assertEquals(1024L * 1024 * 1024, maximumFileSizeFor(null));
	}

	@Test
	public void maximumFileSizeOfMinusOneIsUnlimited() throws Exception {
		assertEquals(-1, maximumFileSizeFor("-1"));
	}

	@Test
	public void maximumFileSizeTakesTheConfiguredValue() throws Exception {
		assertEquals(5L * 1024 * 1024 * 1024, maximumFileSizeFor("5G"));
	}

	/** The maximum file size of a repository manager configured with {@code configured}, or with none if null. */
	private static long maximumFileSizeFor(String configured) throws Exception {
		String baseUrl = "http://localhost:8181/";
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, baseUrl);
		config.setProperty(Settings.SOLR, "off");
		if (configured != null) {
			config.setProperty(Settings.DATA_MAX_FILE_SIZE, configured);
		}
		RepositoryManagerImpl rm = new RepositoryManagerImpl(baseUrl, config);
		try {
			return rm.getMaximumFileSize();
		} finally {
			rm.shutdown();
			deregisterInstance(baseUrl);
		}
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
