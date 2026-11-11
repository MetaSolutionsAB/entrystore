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

package org.entrystore.rest.springboot.controller;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.impl.PublicRepository;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.service.ContextService;
import org.entrystore.rest.springboot.service.ReservedNamesService;
import org.entrystore.rest.springboot.service.SparqlService;
import org.entrystore.rest.springboot.util.SparqlResultFormat;
import org.entrystore.rest.springboot.util.WebResourceUrls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The 503 of a full query limit is produced on the async thread, before any byte of the 200 is written. */
class SparqlControllerConcurrencyLimitTest {

	private static final String QUERY = "SELECT * WHERE { ?s ?p ?o }";

	private Repository repository;
	private SparqlService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		repository = new SailRepository(new MemoryStore());
		repository.init();
		try (RepositoryConnection rc = repository.getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			rc.add(vf.createIRI("http://example.org/s"), vf.createIRI("http://example.org/p"), vf.createLiteral("o"),
					vf.createIRI("http://example.org/store/1"));
		}
		RepositoryManagerImpl repositoryManager = mock(RepositoryManagerImpl.class);
		PublicRepository publicRepository = mock(PublicRepository.class);
		when(repositoryManager.getPublicRepository()).thenReturn(publicRepository);
		when(publicRepository.getConnection()).thenAnswer(_ -> repository.getConnection());
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("http://example.org/store/").toURL());
		service = new SparqlService(repositoryManager, mock(ContextService.class), mock(ReservedNamesService.class),
				10, 64L * 1024 * 1024, 1);
		mvc = MockMvcBuilders.standaloneSetup(new SparqlController(service))
				.setControllerAdvice(new AppExceptionHandler(new WebResourceUrls("http://localhost:8181/store/")))
				.build();
	}

	@AfterEach
	void tearDown() {
		repository.shutDown();
	}

	@Test
	void getSparql_limitReached_answers503WithRetryAfterAndJsonError() throws Exception {
		HoldingOutputStream holder = new HoldingOutputStream();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> held;
			try {
				held = executor.submit(() -> service.runQuery(SparqlResultFormat.SPARQL_RESULTS_JSON, QUERY, null,
						holder));
				assertTrue(holder.writing.await(10, TimeUnit.SECONDS), "The holding query did not start");

				MvcResult started = mvc.perform(get("/sparql").param("query", QUERY))
						.andExpect(request().asyncStarted())
						.andReturn();

				mvc.perform(asyncDispatch(started))
						.andExpect(status().isServiceUnavailable())
						.andExpect(header().string("Retry-After", "5"))
						.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
						.andExpect(jsonPath("$.status").value(503))
						.andExpect(jsonPath("$.error").value("Too many concurrent SPARQL queries; retry later"));
			} finally {
				holder.released.countDown();
			}
			held.get(10, TimeUnit.SECONDS);
		}
	}

	/** Holds the query that writes to it, and with it the only permit, until released. */
	private static final class HoldingOutputStream extends OutputStream {

		final CountDownLatch writing = new CountDownLatch(1);
		final CountDownLatch released = new CountDownLatch(1);

		@Override
		public void write(int b) throws IOException {
			hold();
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			hold();
		}

		private void hold() throws IOException {
			writing.countDown();
			try {
				if (!released.await(30, TimeUnit.SECONDS)) {
					throw new IOException("Test stream was never released");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}
	}
}
