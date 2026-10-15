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

package org.entrystore.repository.backup;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BackupSchedulerTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("exportFormats")
	public void backupFormatKeepsASupportedFormat(RDFFormat format) {
		assertEquals(format, BackupScheduler.backupFormat(configWithFormat(format.getName())));
	}

	private static Stream<RDFFormat> exportFormats() {
		return RepositoryManagerImpl.EXPORT_FORMATS.stream();
	}

	@Test
	public void backupFormatFallsBackToTrixForAFormatWithoutNamedGraphs() {
		assertEquals(RDFFormat.TRIX, BackupScheduler.backupFormat(configWithFormat(RDFFormat.TURTLE.getName())));
	}

	@Test
	public void backupFormatFallsBackToTrixForAnUnknownFormat() {
		assertEquals(RDFFormat.TRIX, BackupScheduler.backupFormat(configWithFormat("NoSuchFormat")));
	}

	private static Config configWithFormat(String format) {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.BACKUP_FORMAT, format);
		return config;
	}

	@Disabled("To be implemented")
	@Test
	public void testRun() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testDelete() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testHasCompression() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testHasMaintenance() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetTimeRegularExpression() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetUpperLimit() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetLowerLimit() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testGetExpiresAfterDays() throws Exception {
		// TODO
	}
}
