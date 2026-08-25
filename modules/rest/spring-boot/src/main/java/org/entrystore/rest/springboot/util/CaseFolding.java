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

package org.entrystore.rest.springboot.util;

import java.util.Locale;

/**
 * Locale-independent case folding for identifier comparison (hostnames, email domains). The bare
 * {@link String#toLowerCase()} uses the JVM default locale, which breaks identifier matching on
 * e.g. Turkish/Azerbaijani JVMs where {@code "I"} folds to a dotless {@code "ı"} — use this helper
 * instead so the locale is fixed in one place and never spelled out at call sites.
 *
 * <p>Not for user-facing display text, where the user's locale is the correct choice. Also not for
 * reserved-name guards: {@code AbstractSsoLoginSuccessHandler#isReservedUsername} deliberately uses
 * {@code equalsIgnoreCase}, because any {@code toLowerCase}-based lookup applies full case mapping
 * and would let variants such as {@code "ADMİN"} (U+0130) past the guard.
 */
public final class CaseFolding {

	private CaseFolding() {
	}

	/** Null-safe {@link Locale#ROOT} lowercasing. */
	public static String toLowerCase(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}
}
