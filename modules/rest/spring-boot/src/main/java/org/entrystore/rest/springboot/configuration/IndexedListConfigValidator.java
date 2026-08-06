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

import org.apache.commons.logging.Log;
import org.entrystore.repository.config.Settings;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Reports, at startup, the config shapes whose meaning changed when the indexed list settings moved from
 * the legacy {@code Config.getStringList} to Spring's map binding. All of them are otherwise silent, and
 * every key below is an allowlist or denylist, so a changed value is a change in who gets in.
 *
 * <ul>
 * <li><b>Bare value alongside indexed entries.</b> {@code PropertiesConfiguration.getPropertyValueCount}
 * returned {@code 1} as soon as the bare key existed, so the bare value was the only value and every
 * indexed entry was ignored. The map binder does the inverse: it drops the bare value and binds the
 * indexed entries. The effective list becomes a different set, not a wider or narrower one.</li>
 * <li><b>Bare value on its own.</b> It no longer binds at all and aborts startup; the resulting
 * {@code BindException} names the record, so this class names the key and the required form instead.</li>
 * <li><b>An entry the legacy reader never read.</b> It probed the literal keys {@code .1}, {@code .2}, …
 * and stopped at the first one absent, so {@code .1} + {@code .3} yielded only {@code .1}, and a list not
 * starting at {@code .1} yielded nothing at all. {@code .0} was never probed, and a zero-padded
 * {@code .01} does not match the literal {@code .1} it looked for. The map binding binds all of them.</li>
 * </ul>
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a bean, for the same reason as
 * {@link LegacyPropertyKeyDetector}: it reports before any bean is created, so the diagnostic still
 * reaches the log when an unrelated bean or bind fails first. It orders itself just ahead of that
 * detector, whose fail-fast throw would otherwise suppress these findings on the same boot.
 *
 * <p>Deliberately logs rather than aborting startup: unlike a renamed key, a list in any of these shapes
 * still binds and still works, it is just not necessarily the list the operator had before.
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

	private final Log log;

	public IndexedListConfigValidator(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(IndexedListConfigValidator.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		for (String key : INDEXED_LIST_KEYS) {
			validateKey(environment, key);
		}
	}

	// Must run after ConfigDataEnvironmentPostProcessor so entrystore.properties (imported via
	// spring.config.import) is part of the Environment when we scan, and just ahead of
	// LegacyPropertyKeyDetector, whose fail-fast throw would otherwise suppress these findings.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private void validateKey(ConfigurableEnvironment environment, String key) {
		SortedSet<String> suffixes = configuredIndexSuffixes(environment, key);
		boolean hasBareValue = environment.containsProperty(key);
		if (suffixes.isEmpty()) {
			if (hasBareValue) {
				log.error("Configuration key '" + key + "' has a bare, un-indexed value. Before 6.1 that was "
						+ "read as a single-element list; it no longer binds and will abort startup. Write it "
						+ "as '" + key + ".1=<value>'.");
			}
			return;
		}
		if (hasBareValue) {
			log.error("Configuration key '" + key + "' has both a bare value and indexed entries " + suffixes
					+ ". Before 6.1 the bare value was used and the indexed entries were ignored; now the "
					+ "bare value is ignored and the indexed entries apply. Remove one of the two forms.");
		}
		// With a bare value present the legacy reader stopped at a count of 1 and never probed .1 at all,
		// so every indexed entry is newly applied, not just the ones after the first hole.
		SortedSet<String> droppedBefore = hasBareValue ? suffixes : entriesTheLegacyReaderDropped(suffixes);
		if (!droppedBefore.isEmpty()) {
			log.error("Configuration key '" + key + "' has entries the previous release never read (found "
					+ suffixes + "; newly applied: " + droppedBefore + "). Before 6.1 counting started at .1 "
					+ "and stopped at the first missing index."
					+ (hasBareValue ? " Remove the bare value above, then renumber" : " Renumber")
					+ " contiguously from .1, without leading zeros, to restore the previous list.");
		}
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
	 * bracket form ({@code ...local[2]}) and the container-native environment-variable form
	 * ({@code ENTRYSTORE_PROXY_WHITELIST_LOCAL_2}). Matching only the dotted form would leave the
	 * diagnostic silent for exactly the deployments most likely to hit this.
	 */
	private static SortedSet<String> configuredIndexSuffixes(ConfigurableEnvironment environment, String key) {
		String dotted = key + ".";
		String bracketed = key + "[";
		String environmentVariable = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_') + "_";
		SortedSet<String> suffixes = new TreeSet<>(indexOrder());
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
				continue;
			}
			for (String name : enumerable.getPropertyNames()) {
				String suffix = indexSuffix(name, dotted, bracketed, environmentVariable);
				if (suffix != null) {
					suffixes.add(suffix);
				}
			}
		}
		return suffixes;
	}

	private static String indexSuffix(String name, String dotted, String bracketed, String environmentVariable) {
		if (name.startsWith(dotted)) {
			return asIndex(name.substring(dotted.length()));
		}
		if (name.startsWith(bracketed) && name.endsWith("]")) {
			return asIndex(name.substring(bracketed.length(), name.length() - 1));
		}
		if (name.startsWith(environmentVariable)) {
			return asIndex(name.substring(environmentVariable.length()));
		}
		return null;
	}

	private static String asIndex(String candidate) {
		return isAsciiDigits(candidate) ? candidate : null;
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
