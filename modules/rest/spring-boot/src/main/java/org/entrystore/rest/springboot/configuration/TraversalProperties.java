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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Binding for the metadata traversal profiles ({@code entrystore.traversal.<profile>.*}), consumed
 * by {@code MetadataService}. Profile names are chosen freely by the operator and supplied by the
 * client at request time, and a profile node is simultaneously an indexed predicate list
 * ({@code entrystore.traversal.foaf.1=...}, {@code .2=...}) and an object with named sub-keys
 * ({@code .max-depth}, {@code .limit}, {@code .repository-scope}, {@code .blacklist.N}) — a layout
 * no typed record structure can express. The binder therefore collects everything below
 * {@code entrystore.traversal} into a flat {@link Map}: when the target value type is scalar,
 * Spring's binder uses the entire remaining dotted path as the map key (the same mechanism that
 * binds {@code logging.level.org.springframework=DEBUG}), so
 * {@code entrystore.traversal.foaf.max-depth=5} binds under the key {@code "foaf.max-depth"}.
 * The typed lookup methods below reconstruct per-profile views from those dotted keys. Other
 * {@code entrystore.*} keys are ignored as unknown fields.
 *
 * <p>Map keys are matched verbatim by the lookup methods below, and are <em>not</em> subject to the
 * relaxed binding a typed component gets. {@link #maxDepth(String)} looks up {@code "<profile>.max-depth"},
 * so write the hyphenated spelling — {@code entrystore.traversal.foaf.maxDepth=5} is not found and the
 * request-supplied depth silently applies instead. A key containing anything beyond lowercase letters,
 * digits and {@code -} may not survive the binder's key canonicalisation as written; bracket it in full
 * to keep it verbatim, {@code entrystore.traversal[my_profile.1]=...}, since the whole remaining path is
 * the map key. {@code TraversalPropertiesTest} pins what the binder actually does with each form.
 *
 * <p>Only the indexed form binds for the predicate and blacklist lists, and it diverges from the legacy
 * reader in two ways. A bare, un-indexed value ({@code entrystore.traversal.foaf=...}) still binds into
 * the map (under the key {@code "foaf"}) but is not returned by {@link #predicates(String)}, so the
 * profile silently disappears — use the indexed form. And a gap is kept: {@code .1} + {@code .3} yields
 * both predicates, where {@code Config.getStringList} stopped counting at the first missing index and
 * dropped {@code .3}. Pinned by {@code TraversalPropertiesTest}.
 *
 * <p>The one exception is a bare {@code entrystore.traversal.<profile>.blacklist=<tuple>}: the legacy
 * reader accepted it as a one-element denylist, and dropping it would make the traversal denylist fail
 * open. It is honoured as a single tuple (with a WARN at bind time recommending the indexed form),
 * ordered before the indexed entries so those win last-wins conflicts in {@code MetadataService}.
 */
@ConfigurationProperties(prefix = "entrystore")
@Slf4j
public record TraversalProperties(Map<String, String> traversal) {

	public TraversalProperties {
		// Copy so the singleton never hands out the binder's mutable LinkedHashMap by reference.
		traversal = (traversal == null) ? Map.of() : Map.copyOf(traversal);
		// Warned once at bind time, not in blacklistTuples: that accessor runs per traversal request.
		traversal.forEach((key, value) -> {
			if (key.endsWith(".blacklist") && !value.isBlank()) {
				log.warn("Configuration key 'entrystore.traversal.{}' has a bare, un-indexed value; it is "
						+ "honoured as a single blacklist tuple so the denylist cannot fail open, but write "
						+ "it as 'entrystore.traversal.{}.1={}'.", key, key, value);
			}
		});
	}

	/**
	 * The predicate URIs of the profile ({@code entrystore.traversal.<profile>.N} keys). Empty when
	 * the profile is not configured — which is also how {@code MetadataService} detects that a
	 * request-supplied name is not a profile.
	 */
	public Set<String> predicates(String profile) {
		return entriesWithNumericSuffix(profile + ".")
				.map(Map.Entry::getValue)
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * The profile's {@code max-depth} setting, empty when unset. A non-numeric value throws
	 * {@link NumberFormatException}, matching the legacy {@code Config.getInt} behaviour.
	 */
	public OptionalInt maxDepth(String profile) {
		return intSetting(profile, "max-depth");
	}

	/**
	 * The profile's {@code limit} setting, empty when unset. A non-numeric value throws
	 * {@link NumberFormatException}, matching the legacy {@code Config.getInt} behaviour.
	 */
	public OptionalInt limit(String profile) {
		return intSetting(profile, "limit");
	}

	/**
	 * The profile's {@code repository-scope} setting, empty when unset. Parses {@code on}/{@code off}
	 * and standard boolean literals (anything unrecognised is false), matching the legacy
	 * {@code Config.getBoolean} behaviour.
	 */
	public Optional<Boolean> repositoryScope(String profile) {
		String value = traversal.get(profile + ".repository-scope");
		if (value == null) {
			return Optional.empty();
		}
		return Optional.of(switch (value.toLowerCase(Locale.ROOT)) {
			case "on" -> true;
			case "off" -> false;
			default -> Boolean.parseBoolean(value);
		});
	}

	/**
	 * The profile's blacklist tuples ({@code entrystore.traversal.<profile>.blacklist.N} keys),
	 * each a comma-separated predicate/object pair. Empty when the profile has no blacklist. A bare
	 * {@code <profile>.blacklist=<tuple>} value is honoured as the first tuple (see the class javadoc).
	 */
	public List<String> blacklistTuples(String profile) {
		String keyPrefix = profile + ".blacklist.";
		String bare = traversal.get(profile + ".blacklist");
		// Sorted by index, not by map iteration order: Map.copyOf hands back an immutable map whose order is
		// randomised per JVM, and MetadataService.loadTraversalBlacklistForProfile puts these into a map keyed
		// by predicate, so the last tuple for a predicate wins. Only this accessor has that contract —
		// predicates() collects into a Set, where a sort would be discarded work on a per-request path.
		// The bare tuple goes first for the same reason: an indexed entry for the same predicate wins.
		return Stream.concat(
						(bare == null || bare.isBlank()) ? Stream.empty() : Stream.of(bare),
						entriesWithNumericSuffix(keyPrefix)
								.map(entry -> Map.entry(
										new BigInteger(entry.getKey().substring(keyPrefix.length())),
										entry.getValue()))
								.sorted(Map.Entry.comparingByKey())
								.map(Map.Entry::getValue))
				.toList();
	}

	private Stream<Map.Entry<String, String>> entriesWithNumericSuffix(String keyPrefix) {
		return traversal.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith(keyPrefix)
						&& isAsciiDigits(entry.getKey().substring(keyPrefix.length())));
	}

	/**
	 * Not {@code StringUtils.isNumeric}, which accepts any Unicode digit: {@code blacklist.٢} would pass
	 * that and then throw from the {@link BigInteger} sort above, turning a config typo into a 500 on
	 * every traversal request for the profile.
	 */
	private static boolean isAsciiDigits(String value) {
		return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	private OptionalInt intSetting(String profile, String setting) {
		String value = traversal.get(profile + "." + setting);
		return value == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value));
	}
}
