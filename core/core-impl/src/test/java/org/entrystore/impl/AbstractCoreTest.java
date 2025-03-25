/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.impl;

import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Manages EntryStore instance(s) as preparation for the tests in entrystore-core-impl.
 *
 * @author Hannes Ebner
 */
public abstract class AbstractCoreTest {

	public RepositoryManagerImpl rm;
	public ContextManager cm;
	public PrincipalManager pm;

	private void setUpEnvironment() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		//config.setProperty(Settings.SOLR_REINDEX_ON_STARTUP, "off");
		//config.setProperty(Settings.SOLR_URL, "/tmp/entrystore-test-solr/");

		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
	}

	@BeforeEach
	public void setUp() {
		setUpEnvironment();
		TestSuite.initDisneySuite(rm);
	}

	public void setUpWithoutSuite() {
		setUpEnvironment();
	}

	@AfterEach
	public void tearDown() {
		rm.shutdown();
		rm = null;
	}

}
