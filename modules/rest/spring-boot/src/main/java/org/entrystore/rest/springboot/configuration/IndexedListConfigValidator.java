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

import java.util.ArrayList;
import java.util.List;
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
 * <p>The legacy-shape rules (gap, {@code .0}, zero-padded, bare value) apply only to the dotted and
 * environment-variable spellings — the only forms {@code Config.getStringList} could ever have been fed.
 * A bracketed entry ({@code ...local[0]}, a YAML sequence, a CLI {@code --key[0]=} override) is
 * Spring-native, always zero-based and carries no changed meaning, so it is accepted silently; only its
 * non-numeric variant is a finding. This validator is the sole guard for these keys — the records
 * deliberately do not re-filter.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than a bean: it runs before any bean is created, so the
 * diagnostic is the first failure rather than being buried under an unrelated bean or bind error. It orders
 * itself just ahead of {@link LegacyPropertyTranslator}, whose own fail-fast throw would otherwise suppress
 * these findings on the same boot.
 *
 * <p>Deliberately aborts rather than logging or dropping entries: honouring a changed list would widen an
 * access-control decision silently on upgrade, and re-implementing the legacy contiguous-from-one
 * semantics per record would keep two readers alive forever. The same policy as
 * {@link LegacyPropertyTranslator} applies — a config whose meaning cannot be kept must be fixed before the
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
	// LegacyPropertyTranslator, whose own fail-fast throw would otherwise suppress these findings.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static void validateKey(ConfigurableEnvironment environment, String key, List<String> findings) {
		IndexedKeySuffixes suffixes = IndexedKeySuffixes.of(environment, key);
		if (!suffixes.nonNumeric().isEmpty()) {
			findings.add("Configuration key '" + key + "' has entries with non-numeric index suffixes "
					+ suffixes.nonNumeric() + ". The previous release never read them and they would bind as "
					+ "active list entries now; list entries must use numeric indices (.1, .2, ...).");
		}
		boolean hasBareValue = suffixes.hasBareValue();
		SortedSet<String> legacyNumeric = suffixes.legacyNumeric();
		boolean hasIndexedEntries = !legacyNumeric.isEmpty() || !suffixes.bracketNumeric().isEmpty();
		if (hasBareValue && !hasIndexedEntries) {
			findings.add("Configuration key '" + key + "' has a bare, un-indexed value. Before 6.1 that "
					+ "was read as a single-element list; it no longer binds. Write it as '" + key
					+ ".1=<value>'.");
			return;
		}
		if (hasBareValue) {
			SortedSet<String> allIndexed = new TreeSet<>(legacyNumeric);
			allIndexed.addAll(suffixes.bracketNumeric());
			findings.add("Configuration key '" + key + "' has both a bare value and indexed entries "
					+ allIndexed + ". Before 6.1 the bare value was used and the indexed entries were "
					+ "ignored; now the bare value would be ignored and the indexed entries would apply. "
					+ "Remove one of the two forms.");
		}
		if (legacyNumeric.isEmpty()) {
			return;
		}
		// With a bare value present the legacy reader stopped at a count of 1 and never probed .1 at all,
		// so every legacy-form entry is newly applied, not just the ones after the first hole.
		SortedSet<String> droppedBefore = hasBareValue
				? legacyNumeric
				: IndexedKeySuffixes.droppedByLegacyReader(legacyNumeric);
		if (!droppedBefore.isEmpty()) {
			findings.add("Configuration key '" + key + "' has entries the previous release never read (found "
					+ legacyNumeric + "; newly applied: " + droppedBefore + "). Before 6.1 counting "
					+ "started at .1 and stopped at the first missing index."
					+ (hasBareValue ? " Remove the bare value above, then renumber" : " Renumber")
					+ " contiguously from .1, without leading zeros, to restore the previous list.");
		}
	}

	private static String buildFailFastMessage(List<String> findings) {
		StringBuilder message = new StringBuilder(
				"EntryStore startup aborted: indexed list settings are configured in shapes whose meaning "
						+ "changed in 6.1. Every key below is an allowlist or denylist, and starting anyway "
						+ "would apply lists that differ from what the previous release applied.\n");
		for (String finding : findings) {
			message.append("  - ").append(finding).append('\n');
		}
		return message.toString();
	}
}
