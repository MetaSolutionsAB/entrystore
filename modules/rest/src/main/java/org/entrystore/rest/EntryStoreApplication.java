/**
 * Copyright (c) 2007-2010
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
import java.util.ArrayList;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;

import org.entrystore.harvester.Harvester;
import org.entrystore.harvester.factory.HarvesterFactoryException;
import org.entrystore.harvesting.oaipmh.harvester.factory.OAIHarvesterFactory;
import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Converter;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.backup.BackupFactory;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.repository.impl.converters.ConverterManagerImpl;
import org.entrystore.repository.impl.converters.LOM2RDFConverter;
import org.entrystore.repository.impl.converters.OAI_DC2RDFGraphConverter;
import org.entrystore.repository.impl.converters.RDF2LOMConverter;
import org.entrystore.repository.test.TestSuite;
import org.entrystore.repository.util.BNodeRewriter;
import org.entrystore.repository.util.DataCorrection;
import org.entrystore.rest.auth.BasicVerifier;
import org.entrystore.rest.auth.CookieVerifier;
import org.entrystore.rest.auth.ExistingUserRedirectAuthenticator;
import org.entrystore.rest.auth.NewUserRedirectAuthenticator;
import org.entrystore.rest.auth.SimpleAuthenticator;
import org.entrystore.rest.filter.JSCallbackFilter;
import org.entrystore.rest.filter.ModificationLockOutFilter;
import org.entrystore.rest.resources.AliasResource;
import org.entrystore.rest.resources.ContextResource;
import org.entrystore.rest.resources.CookieLoginResource;
import org.entrystore.rest.resources.DefaultResource;
import org.entrystore.rest.resources.EntryResource;
import org.entrystore.rest.resources.ExportResource;
import org.entrystore.rest.resources.ExternalMetadataResource;
import org.entrystore.rest.resources.HarvesterResource;
import org.entrystore.rest.resources.ImportResource;
import org.entrystore.rest.resources.LogoutResource;
import org.entrystore.rest.resources.LookupResource;
import org.entrystore.rest.resources.MergeResource;
import org.entrystore.rest.resources.MetadataResource;
import org.entrystore.rest.resources.OpenIdResource;
import org.entrystore.rest.resources.ProxyResource;
import org.entrystore.rest.resources.QuotaResource;
import org.entrystore.rest.resources.RelationResource;
import org.entrystore.rest.resources.RepositoryBackupResource;
import org.entrystore.rest.resources.ResourceResource;
import org.entrystore.rest.resources.SearchResource;
import org.entrystore.rest.resources.SolrResource;
import org.entrystore.rest.resources.SparqlResource;
import org.entrystore.rest.resources.StatisticsResource;
import org.entrystore.rest.resources.StatusResource;
import org.entrystore.rest.resources.UserResource;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Restlet;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Protocol;
import org.restlet.data.Reference;
import org.restlet.ext.openid.AttributeExchange;
import org.restlet.ext.openid.OpenIdVerifier;
import org.restlet.ext.openid.RedirectAuthenticator;
import org.restlet.routing.Filter;
import org.restlet.routing.Router;
import org.restlet.security.Authenticator;
import org.restlet.security.ChallengeAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class to start EntryStore as a Restlet Application.
 *
 * @author Hannes Ebner
 */
public class EntryStoreApplication extends Application {

	public static String KEY = EntryStoreApplication.class.getCanonicalName();
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(EntryStoreApplication.class);
	
	/** Central point for accessing a repository */
	private RepositoryManagerImpl rm;
	
	private ContextManager cm;
	
	private PrincipalManager pm;
	
	private String baseURI; 

	private ArrayList<Harvester> harvesters = new ArrayList<Harvester>();
	
	private BackupScheduler backupScheduler; 

	public EntryStoreApplication(Context parentContext) {
		super(parentContext);
		getContext().getAttributes().put(KEY, this);
		
		/*
		 * should fix the hangs in Acrobat Reader that occur sometimes when
		 * Acrobat tries to fetch parts of files
		 */
		getRangeService().setEnabled(false);
		log.warn("Restlet RangeService deactivated");

		ServletContext sc = (ServletContext) this.getContext().getAttributes().get("org.restlet.ext.ser​vlet.ServletContext");
		// Alt: sc = (ServletContext) getContext().getServerDispatcher().getContext().getAttributes().get("org.restlet.ext.servlet.ServletContext"); 
		if (sc != null) {
			// Application created by ServerServlet, try to get RepositoryManager from ServletContext
			rm = (RepositoryManagerImpl) sc.getAttribute("RepositoryManager");
		}
	
		if (rm != null) {
			// Initialized by a ServletContextListener
			log.info("EntryStore initialized with a ServletContextListener");
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();
			
			// The following objects are fetched from the context attributes,
			// after they have been set in the ContextLoaderListener
			harvesters = (ArrayList) getContext().getAttributes().get("Harvesters");
			backupScheduler = (BackupScheduler) getContext().getAttributes().get("BackupScheduler");
		} else {
			javax.naming.Context env = null;
			URI manualConfigURI = null;
			try {
				env = (javax.naming.Context) new InitialContext().lookup("java:comp/env");
				if (env != null && env.lookup("entrystore.config") != null) {
					manualConfigURI = new File((String) env.lookup("entrystore.config")).toURI();
				}
			} catch (NamingException e) {
				log.warn(e.getMessage());
			}
			
			// Initialize EntryStore
			ConfigurationManager confManager = null;
			try {
				if (manualConfigURI != null) {
					log.info("Manually configured config location at " + manualConfigURI);
					confManager = new ConfigurationManager(manualConfigURI);
				} else {
					log.info("No config location specified, looking within the classpath");
					confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
				}
			} catch (IOException e) {
				log.error("Unable to load configuration: " + e.getMessage());
				return;
			}
		
			Config config = confManager.getConfiguration();
			EntryResource.config = config;
		
			baseURI = config.getString(Settings.BASE_URL);

			Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
			ConverterManagerImpl.register("oai_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("rdn_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("rdf2lom", new RDF2LOMConverter());
			Converter lomRdfConverter = new LOM2RDFConverter();
			ConverterManagerImpl.register("lom2rdf", lomRdfConverter);
			ConverterManagerImpl.register("oai_lom", lomRdfConverter);

			rm = new RepositoryManagerImpl(baseURI, confManager.getConfiguration());
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();

			String storeType = config.getString(Settings.STORE_TYPE, null); 
			if (storeType == null || storeType.equals("memory")) {
				// Create contexts, entries and harvesters
				TestSuite.initDisneySuite(rm);
				TestSuite.addEntriesInDisneySuite(rm);
				TestSuite.HarvesterTestSuite(rm, pm, cm);
				TestSuite.initCourseSuite(rm); 
			}

			// Load and start harvesters
			startHarvesters();
		
			// Load and start backup scheduler
			String backupStatus = rm.getConfiguration().getString(Settings.BACKUP_SCHEDULER, "off");
			if ("off".equals(backupStatus.trim())) {
				log.warn("Backup is disabled in configuration");
			} else {
				startBackupScheduler();
			}

			boolean correct = config.getBoolean("entrystore.repository.store.correct-metadata", false);
			if (correct) {
				DataCorrection mc = new DataCorrection(rm);
				mc.fixMetadataGlobally();
			}
			
			// For old installations: convert plaintext passwords to salted hashes
			new DataCorrection(rm).convertPasswordsToHashes();
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
	public synchronized Restlet createInboundRoot() {
		Config config = rm.getConfiguration();
		Router router = new Router(getContext());
		//router.setDefaultMatchingMode(Template.MODE_STARTS_WITH);
		
		// to prevent unnecessary context-id lookups we route favicon.ico to
		// 404, this may be replaced with some real icon at some later point
		router.attach("/favicon.ico", DefaultResource.class);
		
		// global scope
		router.attach("/search", SearchResource.class);
		router.attach("/sparql", SparqlResource.class);
		router.attach("/proxy", ProxyResource.class);
		
		// authentication resources
		router.attach("/auth/user", UserResource.class);
		router.attach("/auth/cookie", CookieLoginResource.class);
		router.attach("/auth/basic", UserResource.class);
		router.attach("/auth/logout", LogoutResource.class);

		if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID, "off"))) {
			if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_MYOPENID, "off"))) {
				router.attach("/auth/openid/myopenid", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_MYOPENID, false));
				router.attach("/auth/openid/myopenid/signup", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_MYOPENID, true));
				log.info("Authentication via MyOpenID enabled");
			}
			if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_GOOGLE, "off"))) {
				router.attach("/auth/openid/google", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_GOOGLE, false));
				router.attach("/auth/openid/google/signup", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_GOOGLE, true));
				log.info("Authentication via Google enabled");
			}
			if ("on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_YAHOO, "off"))) {
				router.attach("/auth/openid/yahoo", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_YAHOO, false));
				router.attach("/auth/openid/yahoo/signup", createRedirectAuthenticator(OpenIdVerifier.PROVIDER_YAHOO, true));
				log.info("Authentication via Yahoo! enabled");
			}
			// this should work, but it doesn't... something wrong at KTH?
			// router.attach("/auth/openid/kth", createRedirectAuthenticator("https://openid.sys.kth.se/"));
		}
		
		// management/configuration resources
		router.attach("/management/backup", RepositoryBackupResource.class);
		router.attach("/management/status", StatusResource.class);
		router.attach("/management/solr", SolrResource.class);
		
		// context scope
		router.attach("/{context-id}", ContextResource.class);
		router.attach("/{context-id}/sparql", SparqlResource.class);
		router.attach("/{context-id}/export", ExportResource.class);
		router.attach("/{context-id}/import", ImportResource.class);
		router.attach("/{context-id}/merge", MergeResource.class);
		router.attach("/{context-id}/statistics/{stat-type}", StatisticsResource.class);
		router.attach("/{context-id}/entry/{entry-id}", EntryResource.class);
		router.attach("/{context-id}/resource/{entry-id}", ResourceResource.class);
		router.attach("/{context-id}/metadata/{entry-id}", MetadataResource.class);
		router.attach("/{context-id}/cached-external-metadata/{entry-id}", ExternalMetadataResource.class);
		router.attach("/{context-id}/harvester", HarvesterResource.class);
		router.attach("/{context-id}/alias", AliasResource.class);
		router.attach("/{context-id}/alias/{entry-id}", AliasResource.class);
		router.attach("/{context-id}/relation/{entry-id}", RelationResource.class);
		router.attach("/{context-id}/quota", QuotaResource.class);
		router.attach("/{context-id}/lookup", LookupResource.class);

		router.attachDefault(DefaultResource.class);

		ChallengeAuthenticator cookieAuth = new SimpleAuthenticator(getContext(), true, ChallengeScheme.HTTP_COOKIE, "EntryStore", new CookieVerifier(pm), pm);
		ChallengeAuthenticator basicAuth = new SimpleAuthenticator(getContext(), false, ChallengeScheme.HTTP_BASIC, "EntryStore", new BasicVerifier(pm), pm);
		
		ModificationLockOutFilter modLockOut = new ModificationLockOutFilter();
		JSCallbackFilter jsCallback = new JSCallbackFilter();
		
		cookieAuth.setNext(basicAuth);
		basicAuth.setNext(jsCallback);
		jsCallback.setNext(modLockOut);
		modLockOut.setNext(router);

		if (config.getBoolean(Settings.REPOSITORY_REWRITE_BASEREFERENCE, true)) {
			// The following Filter resolves a problem that occurs with reverse
			// proxying, i.e., the internal base reference (as seen e.g. by Tomcat)
			// is different from the external one (as seen e.g. by Apache)
			log.info("Rewriting of base reference is enabled");
			Filter referenceFix = new Filter(getContext()) {
				@Override
				protected int beforeHandle(Request request, Response response) {
					Reference origRef = request.getResourceRef();
					String newBaseRef = rm.getRepositoryURL().toString();
					if (newBaseRef.endsWith("/")) {
						newBaseRef = newBaseRef.substring(0, newBaseRef.length() - 1);
					}
					origRef.setIdentifier(newBaseRef + origRef.getRemainingPart());
					origRef.setBaseRef(newBaseRef);
					return super.beforeHandle(request, response);
				}
			};
			referenceFix.setNext(cookieAuth);
			return referenceFix;
		} else {
			log.warn("Rewriting of base reference has been manually disabled");
			return cookieAuth;
		}
	}
	
	private Authenticator createRedirectAuthenticator(String verifier, boolean createOnDemand) {
		OpenIdVerifier oidv = new OpenIdVerifier(verifier);
		oidv.addRequiredAttribute(AttributeExchange.EMAIL);
		oidv.addRequiredAttribute(AttributeExchange.FIRST_NAME);
		oidv.addRequiredAttribute(AttributeExchange.LAST_NAME);
		RedirectAuthenticator redirAuth;
		if (createOnDemand) {
			redirAuth = new NewUserRedirectAuthenticator(getContext(), oidv, null, rm);
		} else {
			redirAuth = new ExistingUserRedirectAuthenticator(getContext(), oidv, null, rm);
		}
		redirAuth.setOptional(true);
		redirAuth.setNext(OpenIdResource.class);
		return redirAuth;
	}

	/**
	 * This method exists for running stand-alone without a container.
	 */
	public static void main(String[] args) {
		Component component = new Component();
		component.getServers().add(Protocol.HTTP, 8181);
		component.getClients().add(Protocol.FILE);
		component.getClients().add(Protocol.HTTP);
		component.getClients().add(Protocol.HTTPS);
		Context childContext = component.getContext().createChildContext();
		EntryStoreApplication scamApp = new EntryStoreApplication(childContext);
		childContext.getAttributes().put(KEY, scamApp);
		component.getDefaultHost().attach(scamApp);

		try {
			component.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
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
	
	public BackupScheduler getBackupScheduler() {
		return backupScheduler;
	}
	
	public void setBackupScheduler(BackupScheduler scheduler) {
		this.backupScheduler = scheduler;
	}
	
	private void startBackupScheduler() {
		URI userURI = getPM().getAuthenticatedUserURI();
		try {
			getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
			BackupScheduler bs = new BackupFactory(rm).getBackupScheduler();
			if (bs != null) {
				setBackupScheduler(bs);
				bs.run();
			}
		} finally {
			getPM().setAuthenticatedUserURI(userURI);
		}
	}

	public ArrayList<Harvester> getHarvesters() {
		return harvesters; 
	}

	private void startHarvesters() {
		URI realURI = getPM().getAuthenticatedUserURI();
		try {
			getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());
			Set<URI> entries = getCM().getEntries(); 
			java.util.Iterator<URI> iter = entries.iterator(); 
			while (iter.hasNext()) {
				URI entryURI = iter.next();
				Entry entry = getCM().getByEntryURI(entryURI);
				
				if (entry == null) {
					log.warn("Entry with URI " + entryURI + " cannot be found and is null");
					continue;
				}

				if (entry != null && entry.getBuiltinType() == BuiltinType.Context) {
					OAIHarvesterFactory fac = new OAIHarvesterFactory();
					if(fac.isOAIHarvester(entry)) {
						try {
							Harvester har = fac.getHarvester(rm, entry.getEntryURI());
							har.run();
							harvesters.add(har);
						} catch (HarvesterFactoryException e) {
							log.error(e.getMessage());
							e.printStackTrace();
						}
					}
				}
			}
		} finally {
			getPM().setAuthenticatedUserURI(realURI);
		}
	}
	
	@Override
	public synchronized void stop() throws Exception {
		log.info("Shutting down");
		if (rm != null) {
			rm.shutdown();
		}
//		RepositoryManagerImpl repositoryManager = (RepositoryManagerImpl) getContext().getAttributes().get("RepositoryManager");
//		if (repositoryManager == null) {
//			rm.shutdown();
//		} else {
//			// do cleanup in ServletContextListener
//		}
		super.stop();
	}

}