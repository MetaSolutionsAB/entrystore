package org.entrystore.rest.standalone.springboot.service;

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
		if ("on".equalsIgnoreCase(config.getString(Settings.STORE_INIT_WITH_TEST_DATA, "off"))) {
			// Check for the existence of Donald
			Entry donald = repositoryManager.getPrincipalManager().getPrincipalEntry("Donald");
			// We only initialize of test suite has not been loaded before,
			// otherwise we end up with duplicates (if store is persisted)
			if (donald == null) {
				log.info("Initializing store with test data");
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
