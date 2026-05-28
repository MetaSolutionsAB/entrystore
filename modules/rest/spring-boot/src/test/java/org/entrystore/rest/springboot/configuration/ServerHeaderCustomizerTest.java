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

package org.entrystore.rest.springboot.configuration;

import org.entrystore.rest.springboot.configuration.ServerHeaderCustomizer.VersionPrecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerHeaderCustomizerTest {

	@ParameterizedTest(name = "truncateVersion({1}, {0}) == \"{2}\"")
	@CsvSource(value = {
			// precision | version             | expected
			"FULL,         6.0-SNAPSHOT,         6.0-SNAPSHOT",
			"FULL,         6.0.3,                6.0.3",
			"FULL,         6.0.3-SNAPSHOT,       6.0.3-SNAPSHOT",
			"FULL,         6,                    6",
			"FULL,         6.0,                  6.0",
			"FULL,         6.0+build.1,          6.0+build.1",
			"FULL,         '',                   ''",
			"FULL,         ,                     ''",

			"MINOR,        6.0-SNAPSHOT,         6.0",
			"MINOR,        6.0.3,                6.0",
			"MINOR,        6.0.3-SNAPSHOT,       6.0",
			"MINOR,        6,                    6",
			"MINOR,        6.0,                  6.0",
			"MINOR,        6.0+build.1,          6.0",
			"MINOR,        '',                   ''",
			"MINOR,        ,                     ''",

			"MAJOR,        6.0-SNAPSHOT,         6",
			"MAJOR,        6.0.3,                6",
			"MAJOR,        6.0.3-SNAPSHOT,       6",
			"MAJOR,        6,                    6",
			"MAJOR,        6.0,                  6",
			"MAJOR,        6.0+build.1,          6",
			"MAJOR,        '',                   ''",
			"MAJOR,        ,                     ''",

			"NONE,         6.0-SNAPSHOT,         ''",
			"NONE,         6.0.3,                ''",
			"NONE,         6.0.3-SNAPSHOT,       ''",
			"NONE,         6,                    ''",
			"NONE,         '',                   ''",
			"NONE,         ,                     ''",

			// Weird inputs: empty/non-digit segments and leading dots
			"MINOR,        6..1,                 6",
			"MINOR,        .6,                   6",
			"MINOR,        6.,                   6",
			"MAJOR,        v6.0,                 ''",
			"MAJOR,        '   ',                ''",
	}, nullValues = "null")
	void truncateVersion_returnsExpectedSuffix(VersionPrecision precision, String version, String expected) {
		assertEquals(expected, ServerHeaderCustomizer.truncateVersion(version, precision));
	}

	@Test
	void composeDefault_nonePrecision_returnsBareEntryStore() {
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault("6.0-SNAPSHOT", VersionPrecision.NONE));
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault(null, VersionPrecision.NONE));
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault("", VersionPrecision.NONE));
	}

	@Test
	void composeDefault_emptySuffix_returnsBareEntryStoreWithoutSlash() {
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault(null, VersionPrecision.FULL));
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault("", VersionPrecision.MINOR));
		assertEquals("EntryStore", ServerHeaderCustomizer.composeDefault("v6", VersionPrecision.MAJOR));
	}

	@Test
	void composeDefault_nonEmptySuffix_returnsWithSlash() {
		assertEquals("EntryStore/6.0-SNAPSHOT", ServerHeaderCustomizer.composeDefault("6.0-SNAPSHOT", VersionPrecision.FULL));
		assertEquals("EntryStore/6.0", ServerHeaderCustomizer.composeDefault("6.0.3-SNAPSHOT", VersionPrecision.MINOR));
		assertEquals("EntryStore/6", ServerHeaderCustomizer.composeDefault("6.0-SNAPSHOT", VersionPrecision.MAJOR));
	}

	@Test
	void resolveServerHeader_overrideSet_returnsOverrideVerbatim() {
		Supplier<String> versionSupplier = () -> "6.0-SNAPSHOT";
		assertEquals("Acme", ServerHeaderCustomizer.resolveServerHeader("Acme", VersionPrecision.FULL, versionSupplier));
		assertEquals("Acme", ServerHeaderCustomizer.resolveServerHeader("Acme", VersionPrecision.NONE, versionSupplier));
		assertEquals("anything goes",
				ServerHeaderCustomizer.resolveServerHeader("anything goes", VersionPrecision.MAJOR, versionSupplier));
		// override wins even when paired with a non-default precision — precision is ignored.
		assertEquals("MyServer/1.2.3-build7",
				ServerHeaderCustomizer.resolveServerHeader("MyServer/1.2.3-build7", VersionPrecision.MAJOR, versionSupplier));
		assertEquals("MyServer/1.2.3-build7",
				ServerHeaderCustomizer.resolveServerHeader("MyServer/1.2.3-build7", VersionPrecision.MINOR, versionSupplier));
	}

	@Test
	void resolveServerHeader_overrideBlank_usesDefault() {
		Supplier<String> versionSupplier = () -> "6.0-SNAPSHOT";
		assertEquals("EntryStore/6.0-SNAPSHOT",
				ServerHeaderCustomizer.resolveServerHeader("", VersionPrecision.FULL, versionSupplier));
		assertEquals("EntryStore/6",
				ServerHeaderCustomizer.resolveServerHeader(null, VersionPrecision.MAJOR, versionSupplier));
		assertEquals("EntryStore/6.0",
				ServerHeaderCustomizer.resolveServerHeader("   ", VersionPrecision.MINOR, versionSupplier));
	}

	@Test
	void resolveServerHeader_supplierThrows_fallsBackToBareEntryStore() {
		Supplier<String> throwing = () -> { throw new IllegalStateException("VERSION.txt missing"); };
		assertEquals("EntryStore", ServerHeaderCustomizer.resolveServerHeader("", VersionPrecision.FULL, throwing));
		assertEquals("EntryStore", ServerHeaderCustomizer.resolveServerHeader(null, VersionPrecision.MAJOR, throwing));
	}
}
