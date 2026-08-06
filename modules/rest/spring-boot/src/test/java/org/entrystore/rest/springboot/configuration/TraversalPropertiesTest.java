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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalPropertiesTest {

	// Pins the binder behaviour the whole record relies on: with a scalar map value type, the
	// binder uses the entire remaining dotted path as the map key (the logging.level mechanism),
	// so profile sub-keys arrive as "myprofile.max-depth" rather than nested structures.
	@Test
	void binder_bindsDottedKeysIntoFlatMap() {
		TraversalProperties properties = bind(Map.of(
				"entrystore.traversal.myprofile.1", "http://purl.org/dc/terms/relation",
				"entrystore.traversal.myprofile.2", "http://purl.org/dc/terms/isPartOf",
				"entrystore.traversal.myprofile.max-depth", "5",
				"entrystore.traversal.myprofile.blacklist.1", "rdf:type,foaf:Person"));

		assertEquals(Set.of("http://purl.org/dc/terms/relation", "http://purl.org/dc/terms/isPartOf"),
				properties.predicates("myprofile"));
		assertEquals(OptionalInt.of(5), properties.maxDepth("myprofile"));
		assertEquals(List.of("rdf:type,foaf:Person"), properties.blacklistTuples("myprofile"));
	}

	@Test
	void binder_bracketedProfileName_isKeptVerbatim() {
		// The escape hatch the javadoc points operators at: whatever canonicalisation does to a plain key,
		// a bracketed one binds exactly as written, so predicates() finds the profile.
		TraversalProperties properties = bind(Map.of(
				"entrystore.traversal[my_profile.1]", "http://example.com/p1"));

		assertEquals(Set.of("http://example.com/p1"), properties.predicates("my_profile"));
	}

	@Test
	void binder_camelCasedSubKey_isNotFoundByTheHyphenatedLookup() {
		// Characterisation: maxDepth(String) looks up "<profile>.max-depth", so the camelCase spelling is
		// silently ignored however the binder stores it. Pins the advice in the record javadoc.
		TraversalProperties properties = bind(Map.of("entrystore.traversal.foaf.maxDepth", "5"));

		assertEquals(OptionalInt.empty(), properties.maxDepth("foaf"));
	}

	@Test
	void nonAsciiDigitSuffix_isNotTreatedAsAnIndex() {
		// StringUtils.isNumeric would accept this and then throw from the BigInteger sort, turning a config
		// typo into a 500 on every traversal request for the profile.
		TraversalProperties properties = new TraversalProperties(Map.of(
				"p.blacklist.٢", "rdf:type,foaf:Person",
				"p.blacklist.1", "rdf:type,foaf:Agent"));

		assertEquals(List.of("rdf:type,foaf:Agent"), properties.blacklistTuples("p"));
	}

	@Test
	void predicates_excludeBlacklistAndNamedSubKeys() {
		TraversalProperties properties = new TraversalProperties(Map.of(
				"myprofile.1", "http://example.com/p1",
				"myprofile.max-depth", "5",
				"myprofile.limit", "100",
				"myprofile.repository-scope", "off",
				"myprofile.blacklist.1", "rdf:type,foaf:Person"));

		assertEquals(Set.of("http://example.com/p1"), properties.predicates("myprofile"));
	}

	@Test
	void predicates_unknownProfile_returnsEmptySet() {
		TraversalProperties properties = new TraversalProperties(Map.of("myprofile.1", "http://example.com/p1"));

		assertTrue(properties.predicates("other").isEmpty());
	}

	@Test
	void predicates_gapInIndexedForm_keepsPostGapEntries() {
		// Divergence from the legacy PropertiesConfiguration, which stopped counting at the first
		// missing index: the map binding keeps entries after a gap.
		TraversalProperties properties = new TraversalProperties(Map.of(
				"myprofile.1", "http://example.com/p1",
				"myprofile.3", "http://example.com/p3"));

		assertEquals(Set.of("http://example.com/p1", "http://example.com/p3"),
				properties.predicates("myprofile"));
	}

	@Test
	void blacklistTuples_areReturnedInIndexOrderNotMapOrder() {
		// Map.copyOf randomises iteration order per JVM, and MetadataService puts these into a map keyed
		// by predicate where the last tuple wins — so the winner has to be .10 on every run, not whichever
		// entry the salt happened to put last. The .10 case also pins numeric rather than lexicographic
		// ordering, which would sort it between .1 and .2.
		TraversalProperties properties = new TraversalProperties(Map.of(
				"p.blacklist.1", "rdf:type,foaf:Person",
				"p.blacklist.2", "rdf:type,foaf:Agent",
				"p.blacklist.10", "rdf:type,foaf:Organization"));

		assertEquals(List.of("rdf:type,foaf:Person", "rdf:type,foaf:Agent", "rdf:type,foaf:Organization"),
				properties.blacklistTuples("p"));
	}

	@Test
	void intSettings_absent_returnEmpty() {
		TraversalProperties properties = new TraversalProperties(Map.of("myprofile.1", "http://example.com/p1"));

		assertEquals(OptionalInt.empty(), properties.maxDepth("myprofile"));
		assertEquals(OptionalInt.empty(), properties.limit("myprofile"));
	}

	@Test
	void intSettings_malformedValue_throwsLikeLegacyGetInt() {
		TraversalProperties properties = new TraversalProperties(Map.of("myprofile.max-depth", "banana"));

		assertThrows(NumberFormatException.class, () -> properties.maxDepth("myprofile"));
	}

	@ParameterizedTest(name = "repository-scope={0} -> {1}")
	@CsvSource({"on, true", "off, false", "true, true", "false, false", "garbage, false"})
	void repositoryScope_parsesOnOffAndBooleanLiterals(String configured, boolean expected) {
		// Anything unrecognised is false, reproducing PropertiesConfiguration.getBoolean.
		assertEquals(Optional.of(expected),
				new TraversalProperties(Map.of("p.repository-scope", configured)).repositoryScope("p"));
	}

	@Test
	void repositoryScope_absent_returnsEmpty() {
		assertEquals(Optional.empty(), new TraversalProperties(Map.of()).repositoryScope("p"));
	}

	private static TraversalProperties bind(Map<String, String> source) {
		return new Binder(new MapConfigurationPropertySource(source))
				.bind("entrystore", Bindable.of(TraversalProperties.class))
				.get();
	}
}
