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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Switches for user-initiated sign-up ({@code entrystore.auth.signup}) and password reset
 * ({@code entrystore.auth.password-reset}), both off by default. {@code DisabledRouteFilter} answers 404 on every
 * route of a disabled feature, as 5.x left them unrouted.
 *
 * <p>Both accept true/on/yes/1 and false/off/no/0, and a blank value means off; an unrecognised value fails
 * startup. Each key is also the prefix of further keys (e.g. {@code entrystore.auth.signup.whitelist.1}), which
 * these scalar components ignore.
 */
@ConfigurationProperties(prefix = "entrystore.auth")
public record AuthFeatureProperties(
		// Boxed, so that a blank value binds the default instead of failing startup.
		@DefaultValue("false") Boolean signup,
		@DefaultValue("false") Boolean passwordReset) {
}
