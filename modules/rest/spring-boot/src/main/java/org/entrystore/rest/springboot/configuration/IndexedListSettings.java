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
import org.apache.commons.logging.Log;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.util.CaseFolding;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Reads the indexed list settings ({@code key.1}, {@code key.2}, …) the way 5.x's {@code Config.getStringList}
 * did, and reports at startup how a list in a 5.x shape is read. Every consumer reads these settings through
 * {@link #read} or {@link #readHosts}. No property source is modified.
 *
 * <p>Each property source contributes a bare value and indexed entries:
 * <ul>
 * <li>A config file ({@code entrystore.properties}, a YAML file) is read by exact names, as 5.x read its file:
 * {@code key}, {@code key.N} and the YAML-list form {@code key[N]}. Any other name Boot would relate to the key
 * ({@code WHITELIST.1}, {@code white-list.1}, {@code .1-}, {@code .-1}, {@code ..1}, {@code .[1]}, {@code .01},
 * {@code .0}) is ignored.</li>
 * <li>Environment variables and non-enumerable sources are matched by Boot, as for the binder; another source
 * (the command line, SPRING_APPLICATION_JSON) accepts Boot's relaxed spelling of the key but only an exact
 * {@code .N} or {@code [N]} index. In every source the index is plain ASCII digits without a leading zero,
 * {@code 0} only in the Spring-native forms ({@code [0]}, a YAML or JSON array, {@code KEY_0}).</li>
 * </ul>
 * A null value (e.g. a JSON null) sets nothing, and an unresolvable placeholder is kept literally, both as for
 * the binder. Across sources:
 * <ol>
 * <li>Valid entries are merged by index, the highest-precedence source winning per index, and applied from
 * index 1 up to the first missing one, as in 5.x. Index 0 is zero-based list syntax and is applied as the first
 * element.</li>
 * <li>A bare value ({@code key=value}, an empty one included) is the list {@code [value]}, as in 5.x, and hides
 * the indexed entries of its own and lower-precedence sources. Only a non-empty list applied from
 * higher-precedence sources replaces it, so a command-line or environment override wins over a bare value in
 * {@code entrystore.properties}, and an ignored or unapplied entry never displaces it.</li>
 * </ol>
 *
 * <p>An {@link EnvironmentPostProcessor} so the report is logged once, before any bean reads a list: a WARN when
 * an entry is ignored or shadowed or the bare value is empty, otherwise an INFO for a bare value. Both recommend
 * the contiguous {@code .1}, {@code .2}, … form.
 */
@Slf4j
public final class IndexedListSettings implements EnvironmentPostProcessor, Ordered {

	private static final List<String> KEYS = List.of(
			Settings.AUTH_PASSWORD_WHITELIST,
			Settings.AUTH_PASSWORD_BLACKLIST,
			Settings.AUTH_PASSWORD_RULE_CUSTOM,
			Settings.AUTH_PERMITTED_REDIRECTS,
			Settings.PROXY_WHITELIST_LOCAL,
			Settings.PROXY_WHITELIST_ANONYMOUS,
			Settings.PROXY_REMOTE_RESOURCE_DELETE_WHITELIST,
			Settings.SIGNUP_WHITELIST);

	private static final Pattern INDEX = Pattern.compile("0|[1-9][0-9]{0,8}");

	/** A name that ends in .N or [N] after a non-empty prefix that does not itself end in a dot. */
	private static final Pattern EXACT_INDEX =
			Pattern.compile("(.*[^.])(?:\\.(0|[1-9][0-9]{0,8})|\\[(0|[1-9][0-9]{0,8})])");

	private static final int BARE = -1;

	private static final int INVALID = -2;

	private final Log startupLog;

	public IndexedListSettings(DeferredLogFactory logFactory) {
		this.startupLog = logFactory.getLog(IndexedListSettings.class);
	}

	/** The list configured under {@code key}, read by the rules in the class Javadoc. */
	public static List<String> read(Environment environment, String key) {
		if (!(environment instanceof ConfigurableEnvironment configurable)) {
			throw new IllegalArgumentException("Indexed list settings need a ConfigurableEnvironment");
		}
		return reading(configurable, key).values();
	}

	/** The host list under {@code key}, case-folded, with blank entries skipped rather than matching "". */
	public static Set<String> readHosts(Environment environment, String key) {
		Set<String> hosts = new HashSet<>();
		for (String entry : read(environment, key)) {
			if (entry.isBlank()) {
				log.warn("Skipping blank entry in {}", key);
				continue;
			}
			hosts.add(CaseFolding.toLowerCase(entry));
		}
		return Set.copyOf(hosts);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		for (String key : KEYS) {
			Reading reading;
			try {
				reading = reading(environment, key);
			} catch (IllegalArgumentException e) {
				continue; // a circular placeholder reference; the consumer's read fails with it later
			}
			if (reading.warning() != null) {
				startupLog.warn(reading.warning());
			} else if (reading.info() != null) {
				startupLog.info(reading.info());
			}
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties (imported via
	// spring.config.import) is part of the Environment.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private record Reading(List<String> values, String warning, String info) {}

	/** A valid entry of one source; {@code label} names it in the report, {@code index} is {@link #BARE} or N. */
	private record Found(int rank, String label, Object raw, int index) {}

	/** What one source sets for the key: a bare value or null, valid entries by index, and ignored names. */
	private record Contribution(Found bare, Map<Integer, Found> indexed, List<String> ignored) {

		Contribution() {
			this(null, new TreeMap<>(), new ArrayList<>());
		}

		Contribution withBare(Found found) {
			return bare == null ? new Contribution(found, indexed, ignored) : this;
		}
	}

	private static Reading reading(ConfigurableEnvironment environment, String key) {
		ConfigurationPropertyName keyName = ConfigurationPropertyName.of(key);
		List<ConfigurationPropertySource> sources = new ArrayList<>();
		ConfigurationPropertySources.get(environment).forEach(sources::add);
		List<Contribution> contributions = new ArrayList<>();
		Set<Integer> seenIndices = new TreeSet<>();
		for (int rank = 0; rank < sources.size(); rank++) {
			Contribution contribution = contribution(sources.get(rank), rank, key, keyName, null);
			contributions.add(contribution);
			if (contribution != null) {
				seenIndices.addAll(contribution.indexed().keySet());
			}
		}
		// A non-enumerable source cannot list its names, so it is asked for the ones the others set.
		for (int rank = 0; rank < sources.size(); rank++) {
			if (contributions.get(rank) == null) {
				contributions.set(rank, contribution(sources.get(rank), rank, key, keyName, seenIndices));
			}
		}

		Set<String> ignored = new TreeSet<>();
		Found bare = null;
		Map<Integer, Found> byIndex = new LinkedHashMap<>();
		Map<Integer, Found> topByIndex = new LinkedHashMap<>();
		for (Contribution contribution : contributions) {
			ignored.addAll(contribution.ignored());
			contribution.indexed().forEach(topByIndex::putIfAbsent);
			if (contribution.bare() != null) {
				if (bare == null) {
					bare = contribution.bare();
				} else {
					ignored.add(contribution.bare().label()); // shadowed by a higher-precedence bare value
				}
			}
			// A bare value hides the entries of its own and lower-precedence sources.
			if (bare == null) {
				contribution.indexed().forEach(byIndex::putIfAbsent);
			}
		}
		List<Found> applied = new ArrayList<>();
		for (int index = byIndex.containsKey(0) ? 0 : 1; byIndex.containsKey(index); index++) {
			applied.add(byIndex.get(index));
		}
		List<Found> used = bare != null && applied.isEmpty() ? List.of(bare) : applied;
		if (bare != null && !used.contains(bare)) {
			ignored.add(bare.label());
		}
		topByIndex.values().stream()
				.filter(entry -> !used.contains(entry))
				.forEach(entry -> ignored.add(entry.label()));
		List<String> values = used.stream().map(entry -> environment.resolvePlaceholders(entry.raw().toString()))
				.toList();
		return report(key, values, used.contains(bare) ? bare : null, ignored);
	}

	/**
	 * What {@code source} sets for the key. Null for a non-enumerable source while {@code indices} is null, since
	 * it can only be asked for names, which are then {@code key} and the indices the other sources set.
	 */
	private static Contribution contribution(ConfigurationPropertySource source, int rank, String key,
			ConfigurationPropertyName keyName, Set<Integer> indices) {
		PropertySource<?> underlying = source.getUnderlyingSource() instanceof PropertySource<?> propertySource
				? propertySource : null;
		if (underlying instanceof SystemEnvironmentPropertySource
				&& source instanceof IterableConfigurationPropertySource iterable) {
			return byBootNames(source, rank, keyName, iterable.stream().toList());
		}
		if (underlying instanceof EnumerablePropertySource<?> enumerable) {
			return byRawNames(source, enumerable, rank, key, keyName,
					underlying instanceof OriginTrackedMapPropertySource);
		}
		if (indices == null) {
			return null;
		}
		List<ConfigurationPropertyName> names = new ArrayList<>(List.of(keyName));
		for (int index : indices) {
			names.add(ConfigurationPropertyName.of(key + "." + index));
			names.add(ConfigurationPropertyName.of(key + "[" + index + "]"));
		}
		return byBootNames(source, rank, keyName, names);
	}

	/**
	 * Reads an enumerable source by its raw names. A config file is read literally, as 5.x read its file;
	 * elsewhere the key may be spelled as Boot relaxes it, but the index must still be written exactly, because
	 * Boot's names drop characters such as the dash of {@code .-1} or the empty element of {@code ..1}.
	 */
	private static Contribution byRawNames(ConfigurationPropertySource source, EnumerablePropertySource<?> raw,
			int rank, String key, ConfigurationPropertyName keyName, boolean configFile) {
		Contribution contribution = new Contribution();
		Map<Integer, List<String>> spellings = new TreeMap<>();
		List<String> bareSpellings = new ArrayList<>();
		for (String name : raw.getPropertyNames()) {
			if (raw.getProperty(name) == null) {
				continue;
			}
			ConfigurationPropertyName adapted = adapt(name);
			if (adapted == null || !(keyName.equals(adapted) || keyName.isAncestorOf(adapted))) {
				// Boot cannot relate e.g. key.٢ to the key, but a file reader sees it under the key.
				if (configFile && (name.startsWith(key + ".") || name.startsWith(key + "["))) {
					contribution.ignored().add(name);
				}
				continue;
			}
			int index = configFile ? literalIndex(name, key) : relaxedIndex(name, keyName);
			if (index == BARE) {
				bareSpellings.add(name);
			} else if (index == INVALID) {
				contribution.ignored().add(name);
			} else {
				spellings.computeIfAbsent(index, _ -> new ArrayList<>()).add(name);
			}
		}
		String bare = preferred(source, keyName, bareSpellings, List.of(key), contribution.ignored());
		if (bare != null) {
			contribution = contribution.withBare(new Found(rank, bare, raw.getProperty(bare), BARE));
		}
		for (Map.Entry<Integer, List<String>> entry : spellings.entrySet()) {
			String name = preferred(source, adapt(entry.getValue().getFirst()), entry.getValue(),
					List.of(key + "." + entry.getKey(), key + "[" + entry.getKey() + "]"), contribution.ignored());
			contribution.indexed().put(entry.getKey(), new Found(rank, name, raw.getProperty(name), entry.getKey()));
		}
		return contribution;
	}

	/**
	 * Of several valid spellings of one name in one source, the first of {@code exact} that is present, so the key
	 * spelled exactly wins and {@code .N} beats {@code [N]} as in 5.x; else the one Boot picks, else the first.
	 * The others are added to {@code ignored}.
	 */
	private static String preferred(ConfigurationPropertySource source, ConfigurationPropertyName name,
			List<String> spellings, List<String> exact, List<String> ignored) {
		if (spellings.isEmpty()) {
			return null;
		}
		ConfigurationProperty property = source.getConfigurationProperty(name);
		String bootsChoice = property != null && property.getOrigin() instanceof PropertySourceOrigin origin
				&& spellings.contains(origin.getPropertyName()) ? origin.getPropertyName() : spellings.getFirst();
		String chosen = exact.stream().filter(spellings::contains).findFirst().orElse(bootsChoice);
		spellings.stream().filter(spelling -> !spelling.equals(chosen)).forEach(ignored::add);
		return chosen;
	}

	/** The index {@code name} sets in a config file, read literally: {@code key}, {@code key.N}, {@code key[N]}. */
	private static int literalIndex(String name, String key) {
		if (name.equals(key)) {
			return BARE;
		}
		Matcher exact = EXACT_INDEX.matcher(name);
		return exact.matches() && exact.group(1).equals(key) ? index(exact) : INVALID;
	}

	/** The index {@code name} sets with the key spelled as Boot relaxes it and the index written exactly. */
	private static int relaxedIndex(String name, ConfigurationPropertyName keyName) {
		if (keyName.equals(adapt(name))) {
			return BARE;
		}
		Matcher exact = EXACT_INDEX.matcher(name);
		return exact.matches() && keyName.equals(adapt(exact.group(1))) ? index(exact) : INVALID;
	}

	/** 5.x counted from the literal .1, so a dotted .0 was never read; [0] is Spring-native list syntax. */
	private static int index(Matcher exact) {
		if (exact.group(2) != null) {
			return "0".equals(exact.group(2)) ? INVALID : Integer.parseInt(exact.group(2));
		}
		return Integer.parseInt(exact.group(3));
	}

	/** Reads a source by Boot's names, as the binder does: environment variables and non-enumerable sources. */
	private static Contribution byBootNames(ConfigurationPropertySource source, int rank,
			ConfigurationPropertyName keyName, List<ConfigurationPropertyName> names) {
		Contribution contribution = new Contribution();
		boolean variables = source.getUnderlyingSource() instanceof SystemEnvironmentPropertySource;
		for (ConfigurationPropertyName name : names) {
			if (!keyName.equals(name) && !keyName.isAncestorOf(name)) {
				continue;
			}
			ConfigurationProperty property = source.getConfigurationProperty(name);
			if (property == null) {
				continue;
			}
			String label = property.getOrigin() instanceof PropertySourceOrigin origin
					? origin.getPropertyName() : name.toString();
			if (keyName.equals(name)) {
				contribution = contribution.withBare(new Found(rank, label, property.getValue(), BARE));
				continue;
			}
			String index = name.getElement(keyName.getNumberOfElements(), Form.ORIGINAL);
			boolean valid = name.getNumberOfElements() == keyName.getNumberOfElements() + 1
					&& INDEX.matcher(index).matches()
					&& (!"0".equals(index) || variables || name.isLastElementIndexed());
			if (!valid) {
				contribution.ignored().add(label);
			} else {
				contribution.indexed().putIfAbsent(Integer.parseInt(index),
						new Found(rank, label, property.getValue(), Integer.parseInt(index)));
			}
		}
		return contribution;
	}

	private static ConfigurationPropertyName adapt(String name) {
		try {
			return ConfigurationPropertyName.adapt(name, '.');
		} catch (RuntimeException e) {
			return null; // not a name the binder could use either
		}
	}

	/** A WARN when an entry is ignored or the bare value is empty, an INFO for a bare value, otherwise nothing. */
	private static Reading report(String key, List<String> values, Found bare, Set<String> ignored) {
		String recommended = values.isEmpty()
				? key + ".1, " + key + ".2, ..."
				: IntStream.range(0, values.size())
						.mapToObj(i -> key + "." + (i + 1) + "=" + values.get(i))
						.collect(Collectors.joining(", "));
		String applies = "EntryStore list property '" + key + "' applies "
				+ values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", ", "[", "]"));
		boolean emptyBare = bare != null && values.getFirst().isEmpty();
		if (ignored.isEmpty() && !emptyBare) {
			return new Reading(values, null, bare == null ? null
					: applies + " from its bare value; the indexed form " + recommended + " is recommended");
		}
		if (ignored.isEmpty()) {
			return new Reading(values, applies + " from its bare value '" + bare.label() + "'. The empty value is a "
					+ "list of one empty entry; remove the key to configure no list", null);
		}
		return new Reading(values, applies
				+ (bare == null ? "" : " from its bare value '" + bare.label() + "', which wins over indexed entries "
						+ "in its own and lower-precedence sources")
				+ "; ignored: " + ignored
				+ ". Number its entries contiguously from .1 instead: " + recommended
				+ (emptyBare ? ". The empty value is a list of one empty entry; remove the key to configure no list"
						: ""), null);
	}
}
