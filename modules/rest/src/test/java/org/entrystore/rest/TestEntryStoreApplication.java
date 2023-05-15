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

package org.entrystore.rest;

import java.util.Date;
import org.entrystore.ContextManager;
import org.entrystore.Converter;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.converters.ConverterManagerImpl;
import org.entrystore.impl.converters.OAI_DC2RDFGraphConverter;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.SimpleAuthenticator;
import org.entrystore.rest.resources.DefaultResource;
import org.entrystore.rest.resources.StatusResource;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.data.ChallengeScheme;
import org.restlet.routing.Router;
import org.restlet.security.ChallengeAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class to start EntryStore as Restlet Application.
 *
 * @author Hannes Ebner
 */
public class TestEntryStoreApplication extends Application {

	/**
	 * Logger
	 */
	static Logger log = LoggerFactory.getLogger(TestEntryStoreApplication.class);

	public static final String KEY = TestEntryStoreApplication.class.getCanonicalName();
	public static final String ENV_CONFIG_URI = "ENTRYSTORE_CONFIG_URI";
	private static Date startupDate;


	/**
	 * Central point for accessing a repository
	 */
	private RepositoryManagerImpl rm;
	private final ContextManager cm;
	private final PrincipalManager pm;
	private LoginTokenCache loginTokenCache = null;
//	private final UserTempLockoutCache userTempLockoutCache;

	public TestEntryStoreApplication(Config config, Context parentContext, Component component) {
		super(parentContext);
		getContext().getAttributes().put(KEY, this);

		/*
		 * should fix the hangs in Acrobat Reader that occur sometimes when
		 * Acrobat tries to fetch parts of files
		 */
		getRangeService().setEnabled(false);
		log.warn("Restlet RangeService deactivated");

//		if (getServletContext(getContext()) != null) {
//			// Application created by ServerServlet, try to get RepositoryManager from ServletContext
//			rm = (RepositoryManagerImpl) getServletContext(getContext()).getAttribute("RepositoryManager");
//		}

		if (rm != null) {
			// Initialized by a ServletContextListener
			log.info("EntryStore initialized with a ServletContextListener");
			this.cm = rm.getContextManager();
			this.pm = rm.getPrincipalManager();
			this.loginTokenCache = new LoginTokenCache(config);

			// The following objects are fetched from the context attributes,
			// after they have been set in the ContextLoaderListener
//			harvesters = (ArrayList) getContext().getAttributes().get("Harvesters");
//			backupScheduler = (BackupScheduler) getContext().getAttributes().get("BackupScheduler");
//			userTempLockoutCache = null;
		} else {
			Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
			ConverterManagerImpl.register("oai_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("rdn_dc", oaiDcRdfConverter);

			this.rm = new RepositoryManagerImpl("/", config);
			this.cm = rm.getContextManager();
			this.pm = rm.getPrincipalManager();
//			this.userTempLockoutCache = new UserTempLockoutCache(rm, pm);
			this.loginTokenCache = new LoginTokenCache(config);

			// Check for existence of Donald
			Entry donald = rm.getPrincipalManager().getPrincipalEntry("Donald");
			// We only initialize of test suite has not been loaded before,
			// otherwise we end up with duplicates (if store is persisted)
			if (donald == null) {
				log.info("Initializing store with test data");
				// Create contexts, entries, etc for testing purposes
				TestSuite.initDisneySuite(rm);
				TestSuite.addEntriesInDisneySuite(rm);
				// TestSuite.initCourseSuite(rm);
			} else {
				log.warn("Test data is already present, not loading it again");
			}

			log.info("EntryStore startup completed");
		}
	}

	/**
	 * Creates a root Restlet that will receive all incoming calls.
	 *
	 * Because Restlets impose no restrictions on resource design,
	 * the resource classes and the URIs they expose flow naturally
	 * from considerations of ROA design. Below you have a mapping from
	 * URIs to the resources in the REST module.
	 */
	@Override
	public synchronized Restlet createInboundRoot () {
		Config config = rm.getConfiguration();
		Router router = new Router(getContext());
		//router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);

		boolean passwordAuthOff = "off".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD, "on"));

		router.attach("/management/status", StatusResource.class);
		router.attachDefault(DefaultResource.class);

		ChallengeAuthenticator basicAuth = new SimpleAuthenticator(getContext(), false, ChallengeScheme.HTTP_BASIC, "EntryStore", new BasicVerifier(pm, config), pm);
		basicAuth.setNext(router);
		return basicAuth;
	}

	public ContextManager getCM () {
		return this.cm;
	}

	public PrincipalManager getPM () {
		return this.pm;
	}

	public RepositoryManagerImpl getRM () {
		return this.rm;
	}

//	public UserTempLockoutCache getUserTempLockoutCache () {
//		return this.userTempLockoutCache;
//	}


	public static String getVersion () {
		return RepositoryManagerImpl.getVersion();
	}

	public static Date getStartupDate () {
		return startupDate;
	}

	public LoginTokenCache getLoginTokenCache () {
		return loginTokenCache;
	}

	@Override
	public synchronized void stop () throws Exception {
		log.info("Shutting down");
		if (rm != null) {
			rm.shutdown();
		}
//		if (getServletContext(getContext()) != null) {
//			FileCleaningTracker fct = FileCleanerCleanup.getFileCleaningTracker(getServletContext(getContext()));
//			if (fct != null) {
//				log.info("Shutting down file cleaning tracker");
//				fct.exitWhenFinished();
//			}
//		}
		super.stop();
	}
}
