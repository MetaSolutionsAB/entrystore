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

import org.apache.solr.client.api.util.SolrVersion;
import org.entrystore.repository.util.FileOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class SolrVersionMarker {

	static final String SCHEMA_FILENAME = "SCHEMA_VERSION";
	static final String SOLR_FILENAME = "SOLR_VERSION";

	private static final Logger log = LoggerFactory.getLogger(SolrVersionMarker.class);

	private SolrVersionMarker() {
	}

	static File schemaMarker(File dataFolder) {
		return new File(dataFolder, SCHEMA_FILENAME);
	}

	static File solrMarker(File dataFolder) {
		return new File(dataFolder, SOLR_FILENAME);
	}

	// Returns null when the marker file is absent or its content is blank; throws IOException on read failure.
	static String read(File file) throws IOException {
		if (!file.isFile()) {
			return null;
		}
		String content = Files.readString(file.toPath()).strip();
		if (content.isEmpty()) {
			log.warn("Solr version marker {} is blank; treating as missing", file);
			return null;
		}
		return content;
	}

	// Returns true on success, false on failure (already logged).
	static boolean write(File file, String content) {
		try {
			FileOperations.writeStringToFile(file, content);
			return true;
		} catch (IOException e) {
			log.error("Failed to write Solr version marker {}", file, e);
			return false;
		}
	}

	// Returns true when the Solr index needs to be rebuilt — markers absent, inconsistent state,
	// or schema/Solr version drift. A fresh install (no markers) returns true and reindexes zero
	// entries; this also catches first boot after upgrading from a release that didn't write markers.
	static boolean needsReindex(File dataFolder, String currentSchemaVersion) throws IOException {
		File schemaVersionFile = schemaMarker(dataFolder);
		File solrVersionFile = solrMarker(dataFolder);
		boolean schemaExists = schemaVersionFile.isFile();
		boolean solrExists = solrVersionFile.isFile();
		if (!schemaExists && !solrExists) {
			log.info("No Solr version markers found in {}; treating as first boot or upgrade and scheduling reindex.", dataFolder);
			return true;
		}
		if (schemaExists != solrExists) {
			log.warn("Inconsistent Solr version markers (SCHEMA_VERSION present={}, SOLR_VERSION present={}); scheduling full reindex.",
					schemaExists, solrExists);
			return true;
		}
		String persistedSchema = read(schemaVersionFile);
		String persistedSolr = read(solrVersionFile);
		if (currentSchemaVersion.equals(persistedSchema) && solrVersionMatchesCurrent(persistedSolr)) {
			return false;
		}
		log.warn("Solr index was created with EntryStore {} and Solr {} (running: {} and {}); scheduling full reindex.",
				persistedSchema, persistedSolr, currentSchemaVersion, SolrVersion.LATEST);
		return true;
	}

	// Major.minor match is sufficient — patch drift inside a Solr major.minor does not require reindex.
	static boolean solrVersionMatchesCurrent(String persisted) {
		if (persisted == null) {
			return false;
		}
		try {
			SolrVersion persistedVersion = SolrVersion.valueOf(persisted);
			return SolrVersion.LATEST.getMajorVersion() == persistedVersion.getMajorVersion()
					&& SolrVersion.LATEST.getMinorVersion() == persistedVersion.getMinorVersion();
		} catch (IllegalArgumentException e) {
			log.warn("Unparseable persisted Solr version marker '{}'; treating as mismatch", persisted, e);
			return false;
		}
	}
}
