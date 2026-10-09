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
import org.entrystore.repository.config.Settings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Copies {@code org.eclipse.rdf4j.sail.nativerdf.softFailOnCorruptDataAndRepairIndexes} from the configuration into
 * the JVM system property of the same name, as 5.x did, so it can be set in {@code entrystore.properties}. RDF4J reads
 * the system property once, when {@code NativeStore} is first initialized, so {@code EntryStoreConfiguration} makes
 * the repository manager depend on this bean. A system property set with {@code -D} is kept as is, also when another
 * source, such as a command-line argument, sets a different value.
 */
@Slf4j
@Component(Rdf4jSystemPropertiesInitializer.BEAN_NAME)
@RequiredArgsConstructor
public class Rdf4jSystemPropertiesInitializer {

	static final String BEAN_NAME = "rdf4jSystemPropertiesInitializer";

	private final Environment environment;

	@PostConstruct
	void apply() {
		String key = Settings.RDF4J_SOFT_FAIL_ON_CORRUPT_DATA_AND_REPAIR_INDEXES;
		if (System.getProperty(key) == null) {
			System.setProperty(key, environment.getProperty(key, "false"));
		}
		if ("true".equalsIgnoreCase(System.getProperty(key))) {
			log.warn("{}=true: the native store soft-fails on corrupt data and repairs its indexes", key);
		}
	}
}
