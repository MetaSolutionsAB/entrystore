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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import org.apache.commons.fileupload.servlet.FileCleanerCleanup;
import org.apache.commons.io.FileCleaningTracker;
import org.entrystore.ContextManager;
import org.entrystore.Converter;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.converters.ConverterManagerImpl;
import org.entrystore.impl.converters.OAI_DC2RDFGraphConverter;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.auth.SimpleAuthenticator;
import org.entrystore.rest.auth.UserTempLockoutCache;
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

	/** Logger */
	static Logger log = LoggerFactory.getLogger(TestEntryStoreApplication.class);

	public static final String KEY = TestEntryStoreApplication.class.getCanonicalName();
	public static final String ENV_CONFIG_URI = "ENTRYSTORE_CONFIG_URI";
	private static Date startupDate;


	/** Central point for accessing a repository */
	private RepositoryManagerImpl rm;
	private final Component component;
	private final ContextManager cm;
	private final PrincipalManager pm;
	private LoginTokenCache loginTokenCache = null;
	private final UserTempLockoutCache userTempLockoutCache;
	private final Set<String> reservedNames = new HashSet<>();

	public TestEntryStoreApplication(Context parentContext) {
		this(null, parentContext, null);
	}

	public TestEntryStoreApplication(URI configPath, Context parentContext, Component component) {
		super(parentContext);
		Date startupBegin = new Date();
		this.component = component;
		URI configURI = configPath;
		getContext().getAttributes().put(KEY, this);

		/*
		 * should fix the hangs in Acrobat Reader that occur sometimes when
		 * Acrobat tries to fetch parts of files
		 */
		getRangeService().setEnabled(false);
		log.warn("Restlet RangeService deactivated");

		if (getServletContext(getContext()) != null) {
			// Application created by ServerServlet, try to get RepositoryManager from ServletContext
			rm = (RepositoryManagerImpl) getServletContext(getContext()).getAttribute("RepositoryManager");
		}

		if (rm != null) {
			// Initialized by a ServletContextListener
			log.info("EntryStore initialized with a ServletContextListener");
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();

			Config config = rm.getConfiguration();
			this.loginTokenCache = new LoginTokenCache(config);

			// The following objects are fetched from the context attributes,
			// after they have been set in the ContextLoaderListener
//			harvesters = (ArrayList) getContext().getAttributes().get("Harvesters");
//			backupScheduler = (BackupScheduler) getContext().getAttributes().get("BackupScheduler");
			userTempLockoutCache = null;
		} else {
			if (configURI == null) {
				// First we check for a config URI in an environment variable
				String envConfigURI = System.getenv(ENV_CONFIG_URI);
				if (envConfigURI != null) {
					configURI = URI.create(envConfigURI);
				} else {
					// We try the context
					javax.naming.Context env = null;
					try {
						env = (javax.naming.Context) new InitialContext().lookup("java:comp/env");
						if (env != null && env.lookup("entrystore.config") != null) {
							configURI = new File((String) env.lookup("entrystore.config")).toURI();
						}
					} catch (NamingException e) {
						log.warn(e.getMessage());
					}
				}
			}

			// Initialize EntryStore
			ConfigurationManager confManager;
			try {
				if (configURI != null) {
					log.info("Manually specified config location at " + configURI);
					confManager = new ConfigurationManager(configURI);
				} else {
					log.info("No config location specified, looking within classpath");
					confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
				}
			} catch (IOException e) {
				confManager = null;
				log.error("Unable to load configuration: " + e.getMessage());
				System.exit(1);
			}

			Config config = confManager.getConfiguration();
			String baseURI = config.getString(Settings.BASE_URL);
			if (baseURI == null) {
				log.error("No Base URI specified, exiting");
				System.exit(1);
			}

			Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
			ConverterManagerImpl.register("oai_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("rdn_dc", oaiDcRdfConverter);

			this.rm = new RepositoryManagerImpl(baseURI, confManager.getConfiguration());
			this.cm = rm.getContextManager();
			this.pm = rm.getPrincipalManager();
			this.userTempLockoutCache = new UserTempLockoutCache(rm, pm);
			this.loginTokenCache = new LoginTokenCache(confManager.getConfiguration());

			if ("on".equalsIgnoreCase(config.getString(Settings.STORE_INIT_WITH_TEST_DATA, "off"))) {
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
			}

			// Load and start harvesters
//			startHarvesters();

			// Load and start backup scheduler
//			boolean backup = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.BACKUP_SCHEDULER, "off"));
//			if (backup) {
//				log.info("Starting backup scheduler");
//				startBackupScheduler();
//			} else {
//				log.warn("Backup is disabled in configuration");
//			}

		}
		startupDate = new Date();
		log.info("EntryStore startup completed in " + (startupDate.getTime() - startupBegin.getTime()) + " ms");
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
	public synchronized Restlet createInboundRoot() {
		Config config = rm.getConfiguration();
		Router router = new Router(getContext());
		//router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);

		boolean passwordAuthOff = "off".equalsIgnoreCase(config.getString(Settings.AUTH_PASSWORD, "on"));

		// to prevent unnecessary context-id lookups we route favicon.ico to a real icon
		reservedNames.add("favicon.ico");
//		router.attach("/favicon.ico", FaviconResource.class);

		// global scope
		reservedNames.add("echo");
//		router.attach("/echo", EchoResource.class);

		reservedNames.add("lookup");
//		router.attach("/lookup", LookupResource.class);

		reservedNames.add("proxy");
//		router.attach("/proxy", ProxyResource.class);

		reservedNames.add("search");
//		router.attach("/search", SearchResource.class);

		reservedNames.add("sparql");
//		router.attach("/sparql", SparqlResource.class);

		reservedNames.add("validator");
//		router.attach("/validator", ValidatorResource.class);

		// authentication resources
		reservedNames.add("auth");
//		if (!passwordAuthOff) {
//			// we allow login with username/password
//			router.attach("/auth/cookie", CookieLoginResource.class);
//			router.attach("/auth/login", LoginResource.class);
//		}
//		router.attach("/auth/user", UserResource.class);
//		router.attach("/auth/basic", UserResource.class);
//		router.attach("/auth/logout", LogoutResource.class);
//		router.attach("/auth/tokens", TokenResource.class);

		// CAS
//		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_CAS, "off"))) {
//			router.attach("/auth/cas", CasLoginResource.class);
//			log.info("CAS authentication enabled");
//		}

		// SAML
//		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_SAML, "off"))) {
//			router.attach("/auth/saml", SamlLoginResource.class);
//			log.info("SAML authentication enabled");
//		}

		// signup
//		if ("on".equalsIgnoreCase(config.getString(Settings.SIGNUP, "off"))) {
//			router.attach("/auth/signup", SignupResource.class);
//		}

		// password reset
//		if ("on".equalsIgnoreCase(config.getString(Settings.PASSWORD_RESET, "off"))) {
//			router.attach("/auth/pwreset", PasswordResetResource.class);
//		}

		// management/configuration resources
		reservedNames.add("management");
//		router.attach("/management/logging", LoggingResource.class);
		router.attach("/management/status", StatusResource.class);
//		router.attach("/management/solr", SolrResource.class);
//		router.attach("/management/metrics", PerformanceMetricsResource.class);
//		router.attach("/management/shutdown", ShutdownResource.class);

		// context scope
//		router.attach("/{context-id}", ContextResource.class);
//		router.attach("/{context-id}/sparql", SparqlResource.class);
//		router.attach("/{context-id}/export", ExportResource.class);
//		router.attach("/{context-id}/import", ImportResource.class);
//		router.attach("/{context-id}/merge", MergeResource.class);
//		router.attach("/{context-id}/statistics/{stat-type}", StatisticsResource.class);
//		router.attach("/{context-id}/entry/{entry-id}", EntryResource.class);
//		router.attach("/{context-id}/entry/{entry-id}/index", IndexResource.class);
//		router.attach("/{context-id}/entry/{entry-id}/name", NameResource.class);
//		router.attach("/{context-id}/resource/{entry-id}", ResourceResource.class);
//		router.attach("/{context-id}/metadata/{entry-id}", LocalMetadataResource.class);
//		router.attach("/{context-id}/cached-external-metadata/{entry-id}", ExternalMetadataResource.class);
//		router.attach("/{context-id}/harvester", HarvesterResource.class);
//		router.attach("/{context-id}/relations/{entry-id}", RelationResource.class);
//		router.attach("/{context-id}/quota", QuotaResource.class);
//		router.attach("/{context-id}/lookup", LookupResource.class);
//		router.attach("/{context-id}/execute", ExecutionResource.class);
//		router.attach("/{context-id}/proxy", ProxyResource.class);

		// principals scope
//		router.attach("/_principals/groups", GroupResource.class);

		// default resource
		router.attachDefault(DefaultResource.class);

//		CORSFilter corsFilter = new CORSFilter(CORSUtil.getInstance(config));
//		boolean optional = !config.getBoolean(Settings.AUTH_COOKIE_INVALID_TOKEN_ERROR, true);
//		CookieVerifier cookieVerifier = new CookieVerifier(this, rm, null, corsFilter);
//		ChallengeAuthenticator cookieAuth = new SimpleAuthenticator(getContext(), optional, ChallengeScheme.HTTP_COOKIE, "EntryStore", cookieVerifier, pm);

//		IgnoreAuthFilter ignoreAuth = new IgnoreAuthFilter();
//		ModificationLockOutFilter modLockOut = new ModificationLockOutFilter();
//		JSCallbackFilter jsCallback = new JSCallbackFilter();
//		CacheControlFilter cacheControl = new CacheControlFilter();
//		PerformanceMetricsFilter performanceMetrics = new PerformanceMetricsFilter();

//		ignoreAuth.setNext(cookieAuth);

		// If password authentication is disabled we only allow cookie verification (as this may verify auth_tokens
		// generated through a CAS-login), but not basic authentication (as this always requires username/password).
		// Also, we only allow HTTP Basic authentication if explicitly enabled in configuration.
//		if (passwordAuthOff || !config.getBoolean(Settings.AUTH_HTTP_BASIC, false)) {
//			cookieAuth.setNext(jsCallback);
//		} else {
//			ChallengeAuthenticator basicAuth = new SimpleAuthenticator(getContext(), false, ChallengeScheme.HTTP_BASIC, "EntryStore", new BasicVerifier(pm, config), pm);
//			cookieAuth.setNext(basicAuth);
//			basicAuth.setNext(jsCallback);
//		}

//		jsCallback.setNext(performanceMetrics);
//		performanceMetrics.setNext(cacheControl);
//		cacheControl.setNext(modLockOut);

//		if ("on".equalsIgnoreCase(config.getString(Settings.CORS, "off"))) {
//			log.info("Enabling CORS");
//			modLockOut.setNext(corsFilter);
//			corsFilter.setNext(router);
//		} else {
//			modLockOut.setNext(router);
//		}

//		if (config.getBoolean(Settings.REPOSITORY_REWRITE_BASEREFERENCE, true)) {
			// The following Filter resolves a problem that occurs with reverse
			// proxying, i.e., the internal base reference (as seen e.g. by Tomcat)
			// is different from the external one (as seen e.g. by Apache)
//			log.info("Rewriting of base reference is enabled");
//			Filter referenceFix = new Filter(getContext()) {
//				@Override
//				protected int beforeHandle(Request request, Response response) {
//					Reference origRef = request.getResourceRef();
//					String newBaseRef = rm.getRepositoryURL().toString();
//					if (newBaseRef.endsWith("/")) {
//						newBaseRef = newBaseRef.substring(0, newBaseRef.length() - 1);
//					}
//					origRef.setIdentifier(newBaseRef + origRef.getRemainingPart());
//					origRef.setBaseRef(newBaseRef);
//					return super.beforeHandle(request, response);
//				}
//			};
//			referenceFix.setNext(ignoreAuth);
//			return referenceFix;

		ChallengeAuthenticator basicAuth = new SimpleAuthenticator(getContext(), false, ChallengeScheme.HTTP_BASIC, "EntryStore", new BasicVerifier(pm, config), pm);
		basicAuth.setNext(router);
		return basicAuth;
	}

	public ContextManager getCM() {
		return this.cm;
	}

	public PrincipalManager getPM() {
		return this.pm;
	}

	public RepositoryManagerImpl getRM() {
		return this.rm;
	}

	public Set<String> getReservedNames() {
		return this.reservedNames;
	}

	public UserTempLockoutCache getUserTempLockoutCache() {
		return this.userTempLockoutCache;
	}

//	private void startBackupScheduler() {
//		URI userURI = getPM().getAuthenticatedUserURI();
//		try {
//			getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
//			BackupScheduler bs = BackupScheduler.getInstance(rm);
//			if (bs != null) {
//				this.backupScheduler = bs;
//				bs.run();
//			}
//		} finally {
//			getPM().setAuthenticatedUserURI(userURI);
//		}
//	}

//	public ArrayList<Harvester> getHarvesters() {
//		return harvesters;
//	}

//	private void startHarvesters() {
//		URI realURI = getPM().getAuthenticatedUserURI();
//		try {
//			getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
//			Set<URI> entries = getCM().getEntries();
//			java.util.Iterator<URI> iter = entries.iterator();
//			while (iter.hasNext()) {
//				URI entryURI = iter.next();
//				Entry entry = getCM().getByEntryURI(entryURI);
//
//				if (entry == null) {
//					log.warn("Entry with URI " + entryURI + " cannot be found and is null");
//					continue;
//				}
//
//				if (entry != null && entry.getGraphType() == GraphType.Context) {
//					OAIHarvesterFactory fac = new OAIHarvesterFactory();
//					if(fac.isOAIHarvester(entry)) {
//						try {
//							Harvester har = fac.getHarvester(rm, entry.getEntryURI());
//							har.run();
//							harvesters.add(har);
//						} catch (HarvesterFactoryException e) {
//							log.error(e.getMessage());
//						}
//					}
//				}
//			}
//		} finally {
//			getPM().setAuthenticatedUserURI(realURI);
//		}
//	}

	public static String getVersion() {
		return RepositoryManagerImpl.getVersion();
	}

	public static Date getStartupDate() {
		return startupDate;
	}

	public LoginTokenCache getLoginTokenCache() {
		return loginTokenCache;
	}

	@Override
	public synchronized void stop() throws Exception {
		log.info("Shutting down");
		if (rm != null) {
			rm.shutdown();
		}
		if (getServletContext(getContext()) != null) {
			FileCleaningTracker fct = FileCleanerCleanup.getFileCleaningTracker(getServletContext(getContext()));
			if (fct != null) {
				log.info("Shutting down file cleaning tracker");
				fct.exitWhenFinished();
			}
		}
		super.stop();
	}

	public static ServletContext getServletContext(Context context) {
		ServletContext sc = null;
		Context c = context.getServerDispatcher().getContext();
		if (c != null) {
			sc = (ServletContext) c.getAttributes().get("org.restlet.ext.servlet.ServletContext");
		}
		if (sc == null) {
			sc = (ServletContext) context.getAttributes().get("org.restlet.ext.servlet.ServletContext");
		}
		return sc;
	}

	public void shutdownServer() {
		if (this.component != null) {
			log.info("Shutting down server");
			try {
				this.component.stop();
			} catch (Exception e) {
				throw new RuntimeException("Failed to stop server", e);
			}
		} else {
				log.info("Failed to shutdown server, component not set");
		}
	}
}
