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

import org.entrystore.repository.config.Settings;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Aborts startup on the config shapes whose meaning changed when the indexed list settings moved from
 * the legacy {@code Config.getStringList} to Spring's map binding. All of them are otherwise silent, and
 * every key below is an allowlist or denylist, so a changed value is a change in who gets in.
 *
 * <ul>
 * <li><b>Bare value alongside indexed entries.</b> {@code PropertiesConfiguration.getPropertyValueCount}
 * returned {@code 1} as soon as the bare key existed, so the bare value was the only value and every
 * indexed entry was ignored. The map binder does the inverse: it drops the bare value and binds the
 * indexed entries. The effective list becomes a different set, not a wider or narrower one.</li>
 * <li><b>Bare value on its own.</b> It no longer binds at all; the resulting {@code BindException} names
 * the record, so this class names the key and the required form instead.</li>
 * <li><b>An entry the legacy reader never read.</b> It probed the literal keys {@code .1}, {@code .2}, …
 * and stopped at the first one absent, so {@code .1} + {@code .3} yielded only {@code .1}, and a list not
 * starting at {@code .1} yielded nothing at all. {@code .0} was never probed, and a zero-padded
 * {@code .01} does not match the literal {@code .1} it looked for. The map binding binds all of them.</li>
 * <li><b>An entry with a non-numeric index suffix.</b> {@code ...whitelist.local.l=host} (letter l) or a
 * bracketed {@code ...whitelist[partner]} was inert under the legacy reader but binds as an active list
 * entry now.</li>
 * </ul>
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a bean, for the same reason as
 * {@link LegacyPropertyKeyDetector}: it runs before any bean is created, so the diagnostic is the first
 * failure rather than being buried under an unrelated bean or bind error. It orders itself just ahead of
 * that detector, whose own fail-fast throw would otherwise suppress these findings on the same boot.
 *
 * <p>Deliberately aborts rather than logging or dropping entries: honouring a changed list would widen an
 * access-control decision silently on upgrade, and re-implementing the legacy contiguous-from-one
 * semantics per record would keep two readers alive forever. The same policy as
 * {@link LegacyPropertyKeyDetector} applies — a config whose meaning changed must be fixed before the
 * application serves requests — and the exception carries the per-key remedy.
 * {@code entrystore.traversal.*} is out of scope: its profile names are operator-chosen, so a key there
 * would have to be discovered rather than looked up, and its list divergence is documented in the
 * CHANGELOG instead.
 *
 * <p>Adding a future indexed list setting: append its {@code Settings} constant to
 * {@link #INDEXED_LIST_KEYS}.
 */
public final class IndexedListConfigValidator implements EnvironmentPostProcessor, Ordered {

	private static final List<String> INDEXED_LIST_KEYS = List.of(
			Settings.AUTH_PASSWORD_WHITELIST,
			Settings.AUTH_PASSWORD_BLACKLIST,
			Settings.AUTH_PERMITTED_REDIRECTS,
			Settings.PROXY_WHITELIST_LOCAL,
			Settings.PROXY_WHITELIST_ANONYMOUS,
			Settings.PROXY_REMOTE_RESOURCE_DELETE_WHITELIST,
			Settings.SIGNUP_WHITELIST);

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		List<String> findings = new ArrayList<>();
		for (String key : INDEXED_LIST_KEYS) {
			validateKey(environment, key, findings);
		}
		if (!findings.isEmpty()) {
			throw new IllegalStateException(buildFailFastMessage(findings));
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties (imported via
	// spring.config.import) is part of the Environment when we scan, and just ahead of
	// LegacyPropertyKeyDetector, whose own fail-fast throw would otherwise suppress these findings.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static void validateKey(ConfigurableEnvironment environment, String key, List<String> findings) {
		ConfiguredSuffixes suffixes = configuredSuffixes(environment, key);
		if (!suffixes.nonNumeric().isEmpty()) {
			findings.add("Configuration key '" + key + "' has entries with non-numeric index suffixes "
					+ suffixes.nonNumeric() + ". The previous release never read them and they would bind as "
					+ "active list entries now; list entries must use numeric indices (.1, .2, ...).");
		}
		boolean hasBareValue = environment.containsProperty(key);
		if (suffixes.numeric().isEmpty()) {
			if (hasBareValue) {
				findings.add("Configuration key '" + key + "' has a bare, un-indexed value. Before 6.1 that "
						+ "was read as a single-element list; it no longer binds. Write it as '" + key
						+ ".1=<value>'.");
			}
			return;
		}
		if (hasBareValue) {
			findings.add("Configuration key '" + key + "' has both a bare value and indexed entries "
					+ suffixes.numeric() + ". Before 6.1 the bare value was used and the indexed entries were "
					+ "ignored; now the bare value would be ignored and the indexed entries would apply. "
					+ "Remove one of the two forms.");
		}
		// With a bare value present the legacy reader stopped at a count of 1 and never probed .1 at all,
		// so every indexed entry is newly applied, not just the ones after the first hole.
		SortedSet<String> droppedBefore = hasBareValue
				? suffixes.numeric()
				: entriesTheLegacyReaderDropped(suffixes.numeric());
		if (!droppedBefore.isEmpty()) {
			findings.add("Configuration key '" + key + "' has entries the previous release never read (found "
					+ suffixes.numeric() + "; newly applied: " + droppedBefore + "). Before 6.1 counting "
					+ "started at .1 and stopped at the first missing index."
					+ (hasBareValue ? " Remove the bare value above, then renumber" : " Renumber")
					+ " contiguously from .1, without leading zeros, to restore the previous list.");
		}
	}

	private static String buildFailFastMessage(List<String> findings) {
		StringBuilder message = new StringBuilder(
				"EntryStore startup aborted: indexed list settings are configured in shapes whose meaning "
						+ "changed in 6.1. Every key below is an allowlist or denylist, so starting anyway "
						+ "could admit callers the previous configuration excluded.\n");
		for (String finding : findings) {
			message.append("  - ").append(finding).append('\n');
		}
		return message.toString();
	}

	/**
	 * The suffixes the legacy reader never read, assuming no bare value is set. It probed the literal keys
	 * {@code .1}, {@code .2}, … and stopped at the first one absent, so this is everything from the first
	 * hole onwards — and also {@code .0}, which it never probed, and any zero-padded spelling such as
	 * {@code .01}, which does not match the literal key it looked for. All of them bind now, which is why
	 * they are compared as written rather than parsed to a number.
	 */
	private static SortedSet<String> entriesTheLegacyReaderDropped(SortedSet<String> suffixes) {
		SortedSet<String> dropped = new TreeSet<>(suffixes);
		int reached = 1;
		while (dropped.remove(Integer.toString(reached))) {
			reached++;
		}
		return dropped;
	}

	/**
	 * Index suffixes across the three spellings that all bind to the same list: the dotted form, the
	 * bracket form ({@code ...local[2]}) and the container-native environment-variable form. Boot's
	 * {@code SystemEnvironmentPropertyMapper} removes dashes rather than converting them, so
	 * {@code entrystore.proxy.remote-resource.delete.whitelist} binds from
	 * {@code ENTRYSTORE_PROXY_REMOTERESOURCE_DELETE_WHITELIST_1}. Matching only the dotted form would
	 * leave the diagnostic silent for exactly the deployments most likely to hit this.
	 */
	private static ConfiguredSuffixes configuredSuffixes(ConfigurableEnvironment environment, String key) {
		String dotted = key + ".";
		String bracketed = key + "[";
		String environmentVariable = key.toUpperCase(Locale.ROOT).replace("-", "").replace('.', '_') + "_";
		SortedSet<String> numeric = new TreeSet<>(indexOrder());
		SortedSet<String> nonNumeric = new TreeSet<>();
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
				continue;
			}
			for (String name : enumerable.getPropertyNames()) {
				String suffix = rawSuffix(name, dotted, bracketed, environmentVariable);
				if (suffix == null || suffix.isEmpty()) {
					continue;
				}
				if (isAsciiDigits(suffix)) {
					numeric.add(suffix);
				} else {
					nonNumeric.add(suffix);
				}
			}
		}
		return new ConfiguredSuffixes(numeric, nonNumeric);
	}

	private record ConfiguredSuffixes(SortedSet<String> numeric, SortedSet<String> nonNumeric) {}

	private static String rawSuffix(String name, String dotted, String bracketed, String environmentVariable) {
		if (name.startsWith(dotted)) {
			return name.substring(dotted.length());
		}
		if (name.startsWith(bracketed) && name.endsWith("]")) {
			return name.substring(bracketed.length(), name.length() - 1);
		}
		if (name.startsWith(environmentVariable)) {
			return name.substring(environmentVariable.length());
		}
		return null;
	}

	/**
	 * Numeric first, so a gap reads as {@code [1, 2, 10]} rather than the lexicographic {@code [1, 10, 2]},
	 * then by text so that {@code .1} and a zero-padded {@code .01} stay distinct entries.
	 */
	private static Comparator<String> indexOrder() {
		return (left, right) -> {
			int byIndex = new BigInteger(left).compareTo(new BigInteger(right));
			return byIndex != 0 ? byIndex : left.compareTo(right);
		};
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
