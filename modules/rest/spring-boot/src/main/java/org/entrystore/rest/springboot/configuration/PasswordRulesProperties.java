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

import org.entrystore.repository.security.Password;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static java.util.Objects.requireNonNullElse;

/**
 * Binding for the password rules ({@code entrystore.auth.password.rule.*}) that {@link Password#conformsToRules}
 * applies when a password is set. A rule left unset keeps its default from {@link Password#getDefaultRules()},
 * the same defaults as 5.x. The booleans accept true/on/yes/1 and false/off/no/0; any other value fails startup.
 *
 * <p>The custom rules ({@code ...custom.1}, {@code ...custom.2}) are an indexed list of regular expressions,
 * each of which must be found in the password. They are read through {@link IndexedListSettings} and passed to
 * {@link #toRules}, where an invalid expression fails startup.
 */
@ConfigurationProperties(prefix = "entrystore.auth.password.rule")
public record PasswordRulesProperties(
		Boolean uppercase,
		Boolean lowercase,
		Boolean number,
		Boolean symbol,
		Integer minLength) {

	/**
	 * The configured rules with {@code custom} as the custom rules. Each expression is compiled here so a broken
	 * one fails startup instead of every later password check; it is named by its {@code .N} in the contiguous
	 * form the list was read as.
	 */
	public Password.Rules toRules(List<String> custom) {
		for (int i = 0; i < custom.size(); i++) {
			try {
				Pattern.compile(custom.get(i));
			} catch (PatternSyntaxException e) {
				// Not chained: the startup failure report prints only the root cause, which must name the key.
				throw new IllegalArgumentException("Invalid regular expression in "
						+ "entrystore.auth.password.rule.custom." + (i + 1) + ": " + e.getMessage());
			}
		}
		Password.Rules defaults = Password.getDefaultRules();
		return new Password.Rules(
				requireNonNullElse(uppercase, defaults.isUppercase()),
				requireNonNullElse(lowercase, defaults.isLowercase()),
				requireNonNullElse(symbol, defaults.isSymbol()),
				requireNonNullElse(number, defaults.isNumber()),
				requireNonNullElse(minLength, defaults.getMinLength()),
				Set.copyOf(custom));
	}
}
