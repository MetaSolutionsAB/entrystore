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

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.entrystore.impl.RepositoryManagerImpl;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Timed maintenance operations for ENTRYSTORE-1086 (E1/E2/E4): exports the benchmark context,
 * assembles an import ZIP the way the REST export does (export.properties + triples.rdf), imports
 * it into a fresh context (which internally runs the E4 reIndex), and finally reindexes the
 * imported context once more for an isolated reIndex timing.
 */
public class MaintenancePhase {

	public static void run(RepositoryManagerImpl repositoryManager) throws IOException {
		ContextManager contextManager = repositoryManager.getContextManager();
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry sourceContextEntry = contextManager.getContext(BenchmarkCommons.CONTEXT_ALIAS + "_1").getEntry();

		LogUtils.logType("  MAINT ");

		File workDir = Files.createTempDirectory("benchmark-maintenance").toFile();
		try {
			File triplesFile = new File(workDir, "triples.rdf");
			Set<URI> users = new HashSet<>();

			LocalDateTime startExport = LocalDateTime.now();
			contextManager.exportContext(sourceContextEntry, triplesFile, users, false, TriGWriter.class);
			LocalDateTime endExport = LocalDateTime.now();
			LogUtils.logTimeDifference("Exporting context took", startExport, endExport);

			File zipFile = assembleImportZip(repositoryManager, sourceContextEntry, workDir, triplesFile, users);

			Entry importedContextEntry = contextManager.createResource(null, GraphType.Context, null, null);
			LocalDateTime startImport = LocalDateTime.now();
			contextManager.importContext(importedContextEntry, zipFile);
			LocalDateTime endImport = LocalDateTime.now();
			LogUtils.logTimeDifference("Importing context took", startImport, endImport);

			Context importedContext = (Context) importedContextEntry.getResource();
			LocalDateTime startReindex = LocalDateTime.now();
			importedContext.reIndex();
			LocalDateTime endReindex = LocalDateTime.now();
			LogUtils.logTimeDifference("Reindexing context took", startReindex, endReindex);

			LogUtils.log.info("Imported context contains {} entries", importedContext.getEntries().size());
		} finally {
			FileUtils.deleteDirectory(workDir);
		}
	}

	private static File assembleImportZip(RepositoryManagerImpl repositoryManager, Entry contextEntry,
			File workDir, File triplesFile, Set<URI> users) throws IOException {
		Properties props = new Properties();
		props.setProperty("baseURI", repositoryManager.getRepositoryURL().toString());
		props.setProperty("contextEntryURI", contextEntry.getEntryURI().toString());
		props.setProperty("contextResourceURI", contextEntry.getResourceURI().toString());
		props.setProperty("contextMetadataURI", contextEntry.getLocalMetadataURI().toString());
		props.setProperty("contextRelationURI", contextEntry.getRelationURI().toString());
		props.setProperty("containedUsers", users.stream()
				.map(u -> u.toString().substring(u.toString().lastIndexOf('/') + 1))
				.collect(Collectors.joining(",")));

		File zipFile = new File(workDir, "export.zip");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
			zos.putNextEntry(new ZipEntry("export.properties"));
			props.store(zos, null);
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("triples.rdf"));
			Files.copy(triplesFile.toPath(), zos);
			zos.closeEntry();
		}
		return zipFile;
	}
}
