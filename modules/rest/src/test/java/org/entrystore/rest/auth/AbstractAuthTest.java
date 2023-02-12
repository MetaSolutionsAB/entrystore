package org.entrystore.rest.auth;

import static org.mockito.Mockito.mock;

import org.entrystore.ContextManager;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.PropertiesConfiguration;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.junit.After;
import org.junit.Before;

/**
 * Manages EntryStore instance(s) as preparation for the tests in entrystore-core-impl.
 *
 * @author Hannes Ebner
 */
public abstract class AbstractAuthTest {

	RepositoryManagerImpl rm;

	ContextManager cm;

	PrincipalManager pm;

	@Before
	public void setUp() {
		Config config = new PropertiesConfiguration("EntryStore Configuration");
		config.setProperty(Settings.STORE_TYPE, "memory");
		config.setProperty(Settings.BASE_URL, "http://localhost:8181/");
		config.setProperty(Settings.REPOSITORY_REWRITE_BASEREFERENCE, false);
		config.setProperty(Settings.SOLR, "off");
		config.setProperty(Settings.AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS, 5);
		config.setProperty(Settings.AUTH_TEMP_LOCKOUT_DURATION, "1s");
		//config.setProperty(Settings.SOLR_REINDEX_ON_STARTUP, "off");
		//config.setProperty(Settings.SOLR_URL, "/tmp/entrystore-test-solr/");

		rm = new RepositoryManagerImpl("http://localhost:8181/", config);
		pm = mock(PrincipalManager.class);
		cm = rm.getContextManager();
		TestSuite.initDisneySuite(rm);
	}

	@After
	public void tearDown() {
		rm.shutdown();
		rm = null;
	}

}
