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

package org.entrystore.rest.springboot.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.springframework.stereotype.Service;

/**
 * Service responsible for loading test data into the system.
 * This service checks if test data should be loaded upon application startup
 * and prevents duplicate loading of test data if it already exists in the store.
 * <p>
 * The initialization logic is controlled by the "init-with-test-data" configuration
 * property, which determines whether test data should be loaded. If test data is already found,
 * it skips the data loading process.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestDataLoadService {

	private final RepositoryManagerImpl repositoryManager;
	private final Config config;

	@PostConstruct
	public void init() {
		// Runs after class constructor
		if (config.getBoolean(Settings.STORE_INIT_WITH_TEST_DATA, false)) {
			// Check for the existence of Donald
			Entry donald = repositoryManager.getPrincipalManager().getPrincipalEntry("Donald");
			// We only initialize of test suite has not been loaded before,
			// otherwise we end up with duplicates (if store is persisted)
			if (donald == null) {
				log.warn("Loading test users with publicly known passwords because {} is enabled",
						Settings.STORE_INIT_WITH_TEST_DATA);
				loadTestData();
				log.info("Initialized store with test data");
			} else {
				log.warn("Test data is already present, not loading it again");
			}
		}

	}

	/**
	 * Create contexts, entries, etc for testing purposes
	 */
	private void loadTestData() {
		TestSuite.initDisneySuite(repositoryManager);
		TestSuite.addEntriesInDisneySuite(repositoryManager);
		// TestSuite.initCourseSuite(repositoryManager);
	}

}
