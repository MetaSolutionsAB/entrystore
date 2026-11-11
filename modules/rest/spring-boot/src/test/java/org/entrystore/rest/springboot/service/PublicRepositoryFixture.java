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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

/**
 * A public repository filled as PublicRepository fills it: each entry's statements in its own graph (metadata,
 * resource or cached external metadata) and in its context's graph. Three contexts; one triple is in two contexts.
 */
final class PublicRepositoryFixture {

	static final String BASE = "http://example.org/store/";
	static final String PREFIXES = "PREFIX ex: <http://example.org/ns#> "
			+ "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ";
	static final List<String> CONTEXT_GRAPHS = List.of(BASE + "1", BASE + "2", BASE + "3");
	static final String METADATA_GRAPH = BASE + "1/metadata/1";

	private record Entry(String contextId, String graphPath, String turtle) {
	}

	private static final List<Entry> ENTRIES = List.of(
			new Entry("1", "metadata/1",
					"ex:a1 a ex:Award ; ex:laureate ex:p1 ; ex:year 1901 ; ex:label \"A1\"@en, \"A1\"@sv ; "
							+ "ex:next ex:a2 ."),
			new Entry("1", "metadata/2",
					"ex:a2 a ex:Award ; ex:laureate ex:p2 ; ex:year 1902 ; ex:label \"A2\"@en ; ex:next ex:a3 ."),
			new Entry("1", "metadata/3", "ex:p1 ex:name \"P1\" ."),
			new Entry("1", "resource/4", "ex:r1 ex:label \"R1\" ."),
			new Entry("2", "metadata/1", "ex:a3 a ex:Award ; ex:laureate ex:p1 ; ex:year 1903 ."),
			new Entry("2", "metadata/2", "ex:p2 ex:name \"P2\" ."),
			new Entry("2", "metadata/3", "ex:shared ex:name \"Shared\" ."),
			new Entry("2", "metadata/4", "ex:a3 ex:cites <<( ex:p1 ex:name \"P1\" )>> ."),
			new Entry("3", "metadata/1", "ex:shared ex:name \"Shared\" ."),
			new Entry("3", "cached-external-metadata/2", "ex:p3 ex:name \"P3\" ; ex:next ex:p3 ."),
			new Entry("3", "metadata/3", "ex:claim rdf:reifies <<( ex:a1 ex:year 1901 )>> ; ex:source \"S\" ."));

	private PublicRepositoryFixture() {
	}

	/**
	 * @param contextGraphsOnly without the entries' own graphs: the repository as a query must see it when the entry
	 *                          graphs are hidden
	 */
	static Repository create(boolean contextGraphsOnly) throws IOException {
		Repository repository = new SailRepository(new MemoryStore());
		repository.init();
		try (RepositoryConnection rc = repository.getConnection()) {
			ValueFactory vf = rc.getValueFactory();
			for (Entry entry : ENTRIES) {
				Model statements = Rio.parse(new StringReader("@prefix ex: <http://example.org/ns#> . "
						+ "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> . " + entry.turtle()),
						RDFFormat.TURTLE);
				if (contextGraphsOnly) {
					rc.add(statements, vf.createIRI(BASE + entry.contextId()));
				} else {
					rc.add(statements, vf.createIRI(BASE + entry.contextId() + "/" + entry.graphPath()),
							vf.createIRI(BASE + entry.contextId()));
				}
			}
		}
		return repository;
	}
}
