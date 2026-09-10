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

package org.entrystore.impl;

import org.entrystore.AuthorizationException;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;
import java.util.Set;

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
		config.setProperty(Settings.SOLR, "off");
		//config.setProperty(Settings.SOLR_REINDEX_ON_STARTUP, "off");
		//config.setProperty(Settings.SOLR_URL, "/tmp/entrystore-test-solr/");
		customizeConfig(config);

		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
		pm = rm.getPrincipalManager();
		cm = rm.getContextManager();
	}

	/** Hook for a test class that needs a non-default setting; runs before the repository is created. */
	protected void customizeConfig(Config config) {
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

	/**
	 * Decides as {@code user} through the same throwing API every core mutation uses, then restores the
	 * previously authenticated user (the thread-local is static, so a leaked principal bleeds into later tests).
	 */
	protected boolean isAuthorized(User user, Entry entry, AccessProperty prop) {
		URI previous = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(user.getURI());
		try {
			pm.checkAuthenticatedUserAuthorized(entry, prop);
			return true;
		} catch (AuthorizationException e) {
			// A denial for another reason (unresolvable principal, other entry or property) is broken test setup,
			// not the revocation under test, and must not read as a passing assertFalse.
			boolean sameDecision = e.getUser() != null && user.getURI().equals(e.getUser().getURI())
					&& e.getEntry() != null && entry.getEntryURI().equals(e.getEntry().getEntryURI())
					&& e.getAccessProperty() == prop;
			if (!sameDecision) {
				throw new AssertionError("denied for another reason than the decision under test: " + e.getMessage(), e);
			}
			return false;
		} finally {
			pm.setAuthenticatedUserURI(previous);
		}
	}

	/** The rights {@code user} holds on {@code entry}; restores the previously authenticated user afterwards. */
	protected Set<AccessProperty> rightsOf(User user, Entry entry) {
		URI previous = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(user.getURI());
		try {
			return pm.getRights(entry);
		} finally {
			pm.setAuthenticatedUserURI(previous);
		}
	}

}
