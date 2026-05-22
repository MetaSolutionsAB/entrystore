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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrVersionMarkerTest {

	@Test
	void schemaMarkerResolvesToSchemaFilename(@TempDir Path tmp) {
		File f = SolrVersionMarker.schemaMarker(tmp.toFile());
		assertEquals(SolrVersionMarker.SCHEMA_FILENAME, f.getName());
		assertEquals(tmp.toFile(), f.getParentFile());
	}

	@Test
	void solrMarkerResolvesToSolrFilename(@TempDir Path tmp) {
		File f = SolrVersionMarker.solrMarker(tmp.toFile());
		assertEquals(SolrVersionMarker.SOLR_FILENAME, f.getName());
		assertEquals(tmp.toFile(), f.getParentFile());
	}

	@Test
	void readReturnsNullWhenFileMissing(@TempDir Path tmp) throws IOException {
		assertNull(SolrVersionMarker.read(new File(tmp.toFile(), "missing")));
	}

	@Test
	void readReturnsNullWhenFileIsDirectory(@TempDir Path tmp) throws IOException {
		assertNull(SolrVersionMarker.read(tmp.toFile()));
	}

	@Test
	void readReturnsNullWhenFileIsBlank(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		Files.writeString(f.toPath(), "   \n\t\r\n");
		assertNull(SolrVersionMarker.read(f));
	}

	@Test
	void readStripsTrailingCrLf(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		Files.writeString(f.toPath(), "10.0.0\r\n");
		assertEquals("10.0.0", SolrVersionMarker.read(f));
	}

	@Test
	void readStripsTrailingLf(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		Files.writeString(f.toPath(), "10.0.0\n");
		assertEquals("10.0.0", SolrVersionMarker.read(f));
	}

	@Test
	void readHandlesNoTrailingNewline(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		Files.writeString(f.toPath(), "10.0.0");
		assertEquals("10.0.0", SolrVersionMarker.read(f));
	}

	@Test
	void writeReturnsTrueOnSuccess(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		assertTrue(SolrVersionMarker.write(f, "10.0.0"));
		assertEquals("10.0.0", SolrVersionMarker.read(f));
	}

	@Test
	void writeReturnsFalseWhenTargetIsDirectory(@TempDir Path tmp) {
		// Passing an existing directory as the file forces FileOperations.writeStringToFile to IOException.
		assertFalse(SolrVersionMarker.write(tmp.toFile(), "10.0.0"));
	}

	@Test
	void writeOverwritesExistingContent(@TempDir Path tmp) throws IOException {
		File f = new File(tmp.toFile(), "marker");
		Files.writeString(f.toPath(), "old-content", StandardCharsets.UTF_8);
		assertTrue(SolrVersionMarker.write(f, "new-content"));
		assertEquals("new-content", SolrVersionMarker.read(f));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForNull() {
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent(null));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForUnparseable() {
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent("not-a-version"));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForBlank() {
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent(""));
	}

	@Test
	void solrVersionMatchesCurrentReturnsTrueForCurrent() {
		assertTrue(SolrVersionMarker.solrVersionMatchesCurrent(SolrVersion.LATEST.toString()));
	}

	@Test
	void solrVersionLatestRoundTripsThroughValueOf() {
		// Guards against a future SolrVersion.toString() format that valueOf rejects (would otherwise force a destructive reindex on every boot).
		assertEquals(SolrVersion.LATEST, SolrVersion.valueOf(SolrVersion.LATEST.toString()));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForNewerMajor() {
		int otherMajor = SolrVersion.LATEST.getMajorVersion() + 1;
		String otherVersion = otherMajor + "." + SolrVersion.LATEST.getMinorVersion() + "." + SolrVersion.LATEST.getPatchVersion();
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent(otherVersion));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForOlderMajor() {
		int currentMajor = SolrVersion.LATEST.getMajorVersion();
		if (currentMajor == 0) {
			return; // can't go below 0; skip rather than synthesise a bogus case
		}
		String older = (currentMajor - 1) + "." + SolrVersion.LATEST.getMinorVersion() + "." + SolrVersion.LATEST.getPatchVersion();
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent(older));
	}

	@Test
	void solrVersionMatchesCurrentReturnsFalseForSameMajorDifferentMinor() {
		int otherMinor = SolrVersion.LATEST.getMinorVersion() + 1;
		String otherVersion = SolrVersion.LATEST.getMajorVersion() + "." + otherMinor + "." + SolrVersion.LATEST.getPatchVersion();
		assertFalse(SolrVersionMarker.solrVersionMatchesCurrent(otherVersion));
	}

	@Test
	void solrVersionMatchesCurrentReturnsTrueForPatchDrift() {
		int driftedPatch = SolrVersion.LATEST.getPatchVersion() + 99;
		String drifted = SolrVersion.LATEST.getMajorVersion() + "." + SolrVersion.LATEST.getMinorVersion() + "." + driftedPatch;
		assertTrue(SolrVersionMarker.solrVersionMatchesCurrent(drifted));
	}

	@Test
	void needsReindexReturnsTrueWhenNoMarkersPresent(@TempDir Path tmp) throws IOException {
		assertTrue(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}

	@Test
	void needsReindexReturnsTrueWhenOnlySchemaMarkerPresent(@TempDir Path tmp) throws IOException {
		Files.writeString(SolrVersionMarker.schemaMarker(tmp.toFile()).toPath(), "1.0-SNAPSHOT");
		assertTrue(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}

	@Test
	void needsReindexReturnsTrueWhenOnlySolrMarkerPresent(@TempDir Path tmp) throws IOException {
		Files.writeString(SolrVersionMarker.solrMarker(tmp.toFile()).toPath(), SolrVersion.LATEST.toString());
		assertTrue(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}

	@Test
	void needsReindexReturnsTrueWhenSchemaVersionDiffers(@TempDir Path tmp) throws IOException {
		Files.writeString(SolrVersionMarker.schemaMarker(tmp.toFile()).toPath(), "0.9-OLD");
		Files.writeString(SolrVersionMarker.solrMarker(tmp.toFile()).toPath(), SolrVersion.LATEST.toString());
		assertTrue(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}

	@Test
	void needsReindexReturnsTrueWhenSolrMajorVersionDiffers(@TempDir Path tmp) throws IOException {
		Files.writeString(SolrVersionMarker.schemaMarker(tmp.toFile()).toPath(), "1.0-SNAPSHOT");
		int otherMajor = SolrVersion.LATEST.getMajorVersion() + 1;
		String differentMajor = otherMajor + "." + SolrVersion.LATEST.getMinorVersion() + "." + SolrVersion.LATEST.getPatchVersion();
		Files.writeString(SolrVersionMarker.solrMarker(tmp.toFile()).toPath(), differentMajor);
		assertTrue(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}

	@Test
	void needsReindexReturnsFalseWhenBothMarkersMatch(@TempDir Path tmp) throws IOException {
		Files.writeString(SolrVersionMarker.schemaMarker(tmp.toFile()).toPath(), "1.0-SNAPSHOT");
		Files.writeString(SolrVersionMarker.solrMarker(tmp.toFile()).toPath(), SolrVersion.LATEST.toString());
		assertFalse(SolrVersionMarker.needsReindex(tmp.toFile(), "1.0-SNAPSHOT"));
	}
}
