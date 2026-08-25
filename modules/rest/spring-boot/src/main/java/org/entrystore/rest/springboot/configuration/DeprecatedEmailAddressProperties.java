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

/**
 * Bindings for the deprecated {@code entrystore.auth.email.from} and {@code entrystore.auth.email.bcc},
 * superseded by {@code entrystore.smtp.email.from} / {@code .bcc}. {@code EmailSender} prefers the
 * {@code entrystore.smtp.email.*} values and falls back to these, logging a deprecation warning once
 * at startup when a fallback is actually used.
 *
 * <p>A separate record rather than components on {@link SmtpProperties} because the keys sit under a
 * different prefix and Spring Boot has no key-alias mechanism.
 *
 * <p>Deliberately not registered with {@code LegacyPropertyKeyDetector}: that detector flags keys that
 * are no longer read at all, whereas these are still honoured.
 */
@ConfigurationProperties(prefix = "entrystore.auth.email")
public record DeprecatedEmailAddressProperties(String from, String bcc) {}
