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

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The index suffixes of one list setting across the spellings that all bind to the same list, classified into
 * the dotted form ({@code key.1}), the container-native environment-variable form ({@code KEY_1}) — together the
 * only spellings {@code Config.getStringList} could ever have been fed — the Spring-native bracket form
 * ({@code key[0]}, YAML sequences, CLI overrides — which the legacy reader could never parse), and non-numeric
 * suffixes (hazardous in every spelling). {@code bareNames} holds the spellings of the bare, un-indexed value.
 *
 * <p>Names are matched the way the binder matches them, not literally: the dotted/bracket forms go through
 * {@link ConfigurationPropertyName#adapt}, so a relaxed spelling such as
 * {@code ...remoteResource.delete.whitelist.evil} is seen exactly where the binder would bind it, and the
 * environment-variable prefix is compared case-insensitively (Boot's {@code SystemEnvironmentPropertyMapper}
 * removes dashes and accepts lowercase variables, so {@code entrystore.proxy.remote-resource.delete.whitelist}
 * binds from {@code ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_1}). Callers that guard a list must see
 * every spelling the binder accepts.
 */
record IndexedKeySuffixes(SortedSet<String> dottedNumeric, SortedSet<String> environmentNumeric,
		SortedSet<String> bracketNumeric, SortedSet<String> nonNumeric, SortedSet<String> bareNames) {

	/**
	 * Numeric first, so a gap reads as {@code [1, 2, 10]} rather than the lexicographic {@code [1, 10, 2]},
	 * then by text so that {@code .1} and a zero-padded {@code .01} stay distinct entries.
	 */
	static final Comparator<String> INDEX_ORDER = (left, right) -> {
		int byIndex = new BigInteger(left).compareTo(new BigInteger(right));
		return byIndex != 0 ? byIndex : left.compareTo(right);
	};

	static IndexedKeySuffixes of(ConfigurableEnvironment environment, String key) {
		ConfigurationPropertyName canonicalKey = ConfigurationPropertyName.of(key);
		String environmentVariable = key.toUpperCase(Locale.ROOT).replace("-", "").replace('.', '_') + "_";
		IndexedKeySuffixes suffixes = new IndexedKeySuffixes(new TreeSet<>(INDEX_ORDER), new TreeSet<>(INDEX_ORDER),
				new TreeSet<>(INDEX_ORDER), new TreeSet<>(), new TreeSet<>());
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
				continue;
			}
			for (String name : enumerable.getPropertyNames()) {
				suffixes.classify(name, canonicalKey, environmentVariable);
			}
		}
		// Covers non-enumerable sources; the scan above covers spellings that only canonicalise to the key.
		if (environment.containsProperty(key)) {
			suffixes.bareNames.add(key);
		}
		return suffixes;
	}

	boolean hasBareValue() {
		return !bareNames.isEmpty();
	}

	/** The dotted and environment-variable suffixes: the spellings the legacy reader could have been fed. */
	SortedSet<String> legacyNumeric() {
		SortedSet<String> legacy = new TreeSet<>(INDEX_ORDER);
		legacy.addAll(dottedNumeric);
		legacy.addAll(environmentNumeric);
		return legacy;
	}

	/**
	 * The suffixes the legacy reader never read, assuming no bare value is set. It probed the literal keys
	 * {@code .1}, {@code .2}, … and stopped at the first one absent, so this is everything from the first
	 * hole onwards — and also {@code .0}, which it never probed, and any zero-padded spelling such as
	 * {@code .01}, which does not match the literal key it looked for. All of them bind now, which is why
	 * they are compared as written rather than parsed to a number.
	 */
	static SortedSet<String> droppedByLegacyReader(SortedSet<String> suffixes) {
		SortedSet<String> dropped = new TreeSet<>(suffixes);
		int reached = 1;
		while (dropped.remove(Integer.toString(reached))) {
			reached++;
		}
		return dropped;
	}

	private void classify(String name, ConfigurationPropertyName key, String environmentVariable) {
		// Environment-variable branch first: the underscore form is not parseable as a dotted name.
		if (name.regionMatches(true, 0, environmentVariable, 0, environmentVariable.length())) {
			String suffix = name.substring(environmentVariable.length());
			if (!suffix.isEmpty()) {
				(isAsciiDigits(suffix) ? environmentNumeric : nonNumeric).add(suffix);
			}
			return;
		}
		ConfigurationPropertyName adapted;
		try {
			adapted = ConfigurationPropertyName.adapt(name, '.');
		} catch (RuntimeException e) {
			return; // not a name the binder could use either
		}
		if (key.equals(adapted)) {
			// A spelling that canonicalises to the key itself is the bare, un-indexed value.
			bareNames.add(name);
			return;
		}
		if (!key.isAncestorOf(adapted)) {
			// Includes names whose suffix consists entirely of characters adapt() rejects (e.g. a
			// non-ASCII digit such as ٢): adapt classifies that element as EMPTY, leaving a name that is
			// neither the key nor its descendant — and the Binder ignores such a property completely
			// (verified empirically), so it is as inert now as it was under the legacy reader.
			return;
		}
		int keyLength = key.getNumberOfElements();
		if (adapted.getNumberOfElements() == keyLength + 1) {
			String suffix = adapted.getElement(keyLength, Form.ORIGINAL);
			if (!isAsciiDigits(suffix)) {
				nonNumeric.add(suffix);
			} else if (adapted.isLastElementIndexed()) {
				bracketNumeric.add(suffix);
			} else {
				dottedNumeric.add(suffix);
			}
			return;
		}
		// A deeper descendant (e.g. whitelist.1.extra) is never a list entry in any spelling.
		StringBuilder suffix = new StringBuilder();
		for (int i = keyLength; i < adapted.getNumberOfElements(); i++) {
			if (!suffix.isEmpty()) {
				suffix.append('.');
			}
			suffix.append(adapted.getElement(i, Form.ORIGINAL));
		}
		nonNumeric.add(suffix.toString());
	}

	/**
	 * Not {@code StringUtils.isNumeric}, which accepts any Unicode digit: a suffix such as {@code ٢} would
	 * pass that and then blow up in {@link BigInteger}, and it is not an index the legacy reader could
	 * ever have matched either.
	 */
	private static boolean isAsciiDigits(String value) {
		return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
	}
}
