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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.repository.security.Password;
import org.springframework.stereotype.Component;

/**
 * Hands the configured password rules to {@link Password} at startup. {@code EntryStoreConfiguration} makes the
 * repository manager depend on this bean, so the configured rules already apply when core sets the admin
 * password from {@code ENTRYSTORE_ADMIN_PASSWORD} and when test data is loaded.
 */
@Slf4j
@Component(PasswordRulesInitializer.BEAN_NAME)
@RequiredArgsConstructor
public class PasswordRulesInitializer {

	static final String BEAN_NAME = "passwordRulesInitializer";

	private final PasswordRulesProperties properties;

	@PostConstruct
	void applyRules() {
		Password.Rules rules = properties.toRules();
		Password.setRules(rules);
		log.info("Password rules: uppercase={}, lowercase={}, number={}, symbol={}, min-length={}, custom={}",
				rules.isUppercase(), rules.isLowercase(), rules.isNumber(), rules.isSymbol(), rules.getMinLength(),
				rules.getCustom());
	}
}
