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

package org.entrystore;

import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.URISplit;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Times replacing the benchmark context by an import of its own export while a writer keeps creating entries in
 * another context; the write latency shows how long the import holds up other writers. Must run before the context
 * removal phase, which removes the benchmark context.
 */
public class ContextImportBenchmark {

	public static void runBenchmark(RepositoryManagerImpl repositoryManager) throws IOException {
		LogUtils.logType(" IMPORT ");

		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager principalManager = repositoryManager.getPrincipalManager();
		URI admin = principalManager.getAdminUser().getURI();
		principalManager.setAuthenticatedUserURI(admin);

		Context imported = contextManager.getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1");
		File zip = export(repositoryManager, imported);
		try {
			Context written = (Context) contextManager.createResource(null, GraphType.Context, null, null).getResource();
			WriteLatencyProbe probe = new WriteLatencyProbe(written, principalManager, admin);
			LocalDateTime start = LocalDateTime.now();
			LocalDateTime end;
			LogUtils.logDate("Starting importing the context at", start);
			try {
				contextManager.importContext(imported.getEntry(), zip);
				end = LocalDateTime.now();
			} finally {
				probe.stop();
			}
			LogUtils.logDate("Ended importing the context at", end);
			LogUtils.logTimeDifference("Importing the context took", start, end);
			probe.log("the import");
		} finally {
			Files.deleteIfExists(zip.toPath());
		}
	}

	/**
	 * The ZIP the REST export answers with, without data files: the context's statements as TriG and the
	 * {@code export.properties} the import reads.
	 */
	private static File export(RepositoryManagerImpl repositoryManager, Context context) throws IOException {
		Entry contextEntry = context.getEntry();
		File triples = File.createTempFile("benchmark-export-", ".rdf");
		File zip = File.createTempFile("benchmark-export-", ".zip");
		try {
			Set<URI> users = new HashSet<>();
			repositoryManager.getContextManager().exportContext(contextEntry, triples, users, false, TriGWriter.class);

			Properties properties = new Properties();
			properties.put("contextEntryURI", contextEntry.getEntryURI().toString());
			properties.put("contextResourceURI", contextEntry.getResourceURI().toString());
			properties.put("contextMetadataURI", contextEntry.getLocalMetadataURI().toString());
			properties.put("contextRelationURI", contextEntry.getRelationURI().toString());
			properties.put("baseURI", repositoryManager.getRepositoryURL().toString());
			properties.put("containedUsers", containedUsers(repositoryManager.getPrincipalManager(), users));

			try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
				out.putNextEntry(new ZipEntry("triples.rdf"));
				Files.copy(triples.toPath(), out);
				out.closeEntry();
				out.putNextEntry(new ZipEntry("export.properties"));
				properties.store(out, "EntryStore export information");
				out.closeEntry();
			}
			return zip;
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(zip.toPath());
			throw e;
		} finally {
			Files.deleteIfExists(triples.toPath());
		}
	}

	/** {@code id:name} per user as the REST export lists them; the import requires the property to be present. */
	private static String containedUsers(PrincipalManager principalManager, Set<URI> users) {
		StringJoiner joiner = new StringJoiner(",");
		for (URI uri : users) {
			String entry = URISplit.getLastSegment(uri.toString());
			User user = principalManager.getUser(uri);
			if (user != null && user.getName() != null) {
				entry += ":" + user.getName();
			}
			joiner.add(entry);
		}
		return joiner.toString();
	}
}
