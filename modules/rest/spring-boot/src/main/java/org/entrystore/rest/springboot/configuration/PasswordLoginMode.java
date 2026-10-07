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

import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionFailedException;

import java.util.Optional;

/**
 * Local password login, set by {@code entrystore.auth.password}. {@code OFF} disables form login and HTTP Basic;
 * {@code WHITELIST} admits only the listed usernames to password login, by form login and HTTP Basic.
 */
public enum PasswordLoginMode {

	ON, OFF, WHITELIST;

	/**
	 * Accepts {@code whitelist} and the relaxed boolean spellings (true/on/yes/1, false/off/no/0), case-insensitively.
	 * A blank value is {@code ON}, the default; an unrecognised value is empty.
	 */
	public static Optional<PasswordLoginMode> parse(String value) {
		if (value != null && "whitelist".equalsIgnoreCase(value.strip())) {
			return Optional.of(WHITELIST);
		}
		try {
			Boolean enabled = ApplicationConversionService.getSharedInstance().convert(value, Boolean.class);
			return Optional.of(Boolean.FALSE.equals(enabled) ? OFF : ON);
		} catch (ConversionFailedException e) {
			return Optional.empty();
		}
	}
}
