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

package se.kmr.scam.rest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;

import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Guard;
import org.restlet.Restlet;
import org.restlet.Route;
import org.restlet.Router;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.harvester.Harvester;
import se.kmr.scam.harvester.factory.HarvesterFactoryException;
import se.kmr.scam.harvesting.fao.FAO2RDFGraphConverter;
import se.kmr.scam.harvesting.fao.FAOHarvesterFactory;
import se.kmr.scam.harvesting.oaipmh.harvester.factory.OAIHarvesterFactory;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Converter;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.RepositoryManager;
import se.kmr.scam.repository.backup.BackupFactory;
import se.kmr.scam.repository.backup.BackupScheduler;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.impl.converters.ConverterManagerImpl;
import se.kmr.scam.repository.impl.converters.LOM2RDFConverter;
import se.kmr.scam.repository.impl.converters.OAI_DC2RDFGraphConverter;
import se.kmr.scam.repository.impl.converters.RDF2LOMConverter;
import se.kmr.scam.repository.test.TestSuite;
import se.kmr.scam.repository.util.MetadataCorrection;
import se.kmr.scam.rest.auth.BasicGuard;
import se.kmr.scam.rest.resources.AliasResource;
import se.kmr.scam.rest.resources.ContextBackupListResource;
import se.kmr.scam.rest.resources.ContextBackupResource;
import se.kmr.scam.rest.resources.ContextResource;
import se.kmr.scam.rest.resources.DefaultResource;
import se.kmr.scam.rest.resources.EntryResource;
import se.kmr.scam.rest.resources.ExportResource;
import se.kmr.scam.rest.resources.ExternalMetadataResource;
import se.kmr.scam.rest.resources.HarvesterResource;
import se.kmr.scam.rest.resources.ImportResource;
import se.kmr.scam.rest.resources.JSCallbackFilter;
import se.kmr.scam.rest.resources.LoginResource;
import se.kmr.scam.rest.resources.MetadataResource;
import se.kmr.scam.rest.resources.ProxyResource;
import se.kmr.scam.rest.resources.QuotaResource;
import se.kmr.scam.rest.resources.RelationResource;
import se.kmr.scam.rest.resources.RepositoryBackupResource;
import se.kmr.scam.rest.resources.ResourceResource;
import se.kmr.scam.rest.resources.SearchResource;
import se.kmr.scam.rest.resources.SolrResource;
import se.kmr.scam.rest.resources.SparqlResource;
import se.kmr.scam.rest.resources.StatisticsResource;
import se.kmr.scam.rest.resources.StatusResource;

import com.noelios.restlet.ext.servlet.ServletContextAdapter;


/**
 * <p>Applications are guaranteed to receive calls with their base 
 * reference set relatively to the VirtualHost that served them. 
 * This class is both a descriptor able to create the root Restlet 
 * and the actual Restlet that can be attached to one or 
 * more VirtualHost instances.</p>
 * 
 * 
 *<p> Intended conceptual target of a hypertext reference. 
 * "Any information that can be named can be a resource: a document or image, 
 * a temporal service (e.g. "today's weather in Los Angeles"), a collection 
 * of other resources, a non-virtual object (e.g. a person), and so on. In other 
 * words, any concept that might be the target of an author's hypertext 
 * reference must fit within the definition of a resource. The only thing 
 * that is required to be static for a resource is the semantics of the 
 * mapping, since the semantics is what distinguishes one resource from another.
 * " - Roy T. Fielding</p>
 *
 * <p>Root Restlet that will receive all incoming calls from Web browsers etc.</p>
 * 
 * <p>The RESTful S3 service provides three types of resources. Here they are, 
 * with sample URIs for each:
 * <ul>
 * <li>The list of your contexts (https://scam4.org/). There's only
 * one resource of this type.</li>
 * <li>A particular context (https://scam4.org/{context-id}). There can be 
 * many resorces of this type.</li>
 *<li>A particular S3 object(entry) inside a context 
 *(https://scam4.org/{context-id}/{kind/{entry-id}})
 *. There can be infinitely many resources of this type.</li> 
 * </ul>
 * 
 * Each resource can corresponds to the five standard HTTP methods: 
 * GET, POST, PUT, DELETE, HEAD</p>
 *
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 * @author Hannes Ebner
 */
public class ScamApplication extends Application {

	public static String KEY = "se.kmr.scam.rest.ScamApplication";
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(ScamApplication.class);
	/** This object is the central point for accessing a SCAM repository. */
	private RepositoryManagerImpl rm;
	/** Manages all non-system {@link Context} */
	private ContextManager cm;
	/** Manages all system {@link Context} */
	private PrincipalManager pm;
	/** baseURL for the {@link RepositoryManager}*/
	private String domainName; 

	private ArrayList<Harvester> harvesters = new ArrayList<Harvester>();
	
	private BackupScheduler backupScheduler; 

	/**
	 * Constructor
	 * @param parentContext a {@link Context}
	 */
	public ScamApplication(Context parentContext) {
		super(parentContext);
		getContext().getAttributes().put(KEY, this);
		getRangeService().setEnabled(false); // should fix the hangs in Acrobat
												// Reader that occur sometimes
												// when Acrobat tries to fetch
												// parts of files
		log.warn("Restlet RangeService deactivated");

		if (this.getContext() instanceof ServletContextAdapter) {
			// Application created by ServerServlet
			// Try to get RepositoryManager from ServletContext
			ServletContextAdapter adapter = (ServletContextAdapter) getContext();
			ServletContext servletContext = adapter.getServletContext();
			rm = (RepositoryManagerImpl) servletContext.getAttribute("RepositoryManager");
		}
	
		if (rm != null) {
			// SCAM initialized by a ServletContextListener
			log.info("SCAM initialized with a ServletContextListener");
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();
			
			// the following objects are fetched from the context attributes,
			// after they have been set in the ContextLoaderListener
			harvesters = (ArrayList) getContext().getAttributes().get("Harvesters");
			backupScheduler = (BackupScheduler) getContext().getAttributes().get("BackupScheduler");
		} else {
			javax.naming.Context env = null;
			URI manualConfigURI = null;
			try {
				env = (javax.naming.Context) new InitialContext().lookup("java:comp/env");
				if (env != null && env.lookup("scam.config") != null) {
					manualConfigURI = new File((String) env.lookup("scam.config")).toURI();
				}
			} catch (NamingException e) {
				log.warn(e.getMessage());
			}
			
			// Initialize SCAM below
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
				log.error("Unable to load SCAM configuration: " + e.getMessage());
				return;
			}
		
			Config config = confManager.getConfiguration();
		
			// Check the URL in the scam.properties file if you get an error here.
			domainName = config.getString(Settings.SCAM_BASE_URL, "http://scam4.org");

			Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
			ConverterManagerImpl.register("oai_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("rdn_dc", oaiDcRdfConverter);
			ConverterManagerImpl.register("fao_xml", new FAO2RDFGraphConverter());
			ConverterManagerImpl.register("rdf2lom", new RDF2LOMConverter());
			Converter lomRdfConverter = new LOM2RDFConverter();
			ConverterManagerImpl.register("lom2rdf", lomRdfConverter);
			ConverterManagerImpl.register("oai_lom", lomRdfConverter);

			rm = new RepositoryManagerImpl(domainName, confManager.getConfiguration());
			cm = rm.getContextManager();
			pm = rm.getPrincipalManager();

			String storeType = config.getString(Settings.SCAM_STORE_TYPE, null); 
			if(storeType == null || storeType.equals("memory")) {
				// Create context's, entries and harvesters
				TestSuite.initDisneySuite(rm);
				TestSuite.addEntriesInDisneySuite(rm);
				TestSuite.HarvesterTestSuite(rm, pm, cm);
				TestSuite.initCourseSuite(rm); 
			}

			// Load and start harvesters
			startHarvesters();
		
			// Load and start backup scheduler
			String backupStatus = rm.getConfiguration().getString(Settings.SCAM_BACKUP_SCHEDULER, "off");
			if ("off".equals(backupStatus.trim())) {
				log.warn("Backup is disabled in configuration");
			} else {
				startBackupScheduler();
			}

			//		URI currentUserURI = pm.getAuthenticatedUserURI();
			//		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			//		TestSuite.RDF2LOMConverterTestSuite(rm, pm, cm);
			//		pm.setAuthenticatedUserURI(currentUserURI);
		
//			log.info("Starting processing metadata stats");
//			new MetadataStatistics(rm).run();
//			log.info("Done with metadata stats");
		
			boolean correct = config.getBoolean("scam.repository.store.correct-metadata", false);
			if (correct) {
//				new OEAutomaticValidation(rm).validateMetadata(URI.create("http://oe.confolio.org/scam/5"), URI.create("http://oe.confolio.org/scam/5/entry/6365"));
				MetadataCorrection mc = new MetadataCorrection(rm);
				mc.fixMetadataGlobally();
				//mc.fixPrincipalsGlobally();
//				try {
//					mc.printUnvalidatedResources("http://oe.confolio.org/scam/30", new FileWriter("/home/hannes/Desktop/bce.csv"));
//				} catch (IOException e) {
//					e.printStackTrace();
//				}
			}
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
	public synchronized Restlet createRoot() {
		// Create a router Restlet
		Router router = new Router(getContext());
		Route element = router.attach("/search", SearchResource.class);
		element = router.attach("/login", LoginResource.class);
		element = router.attach("/sparql", SparqlResource.class);
		element = router.attach("/proxy", ProxyResource.class);
		element = router.attach("/management/backup", RepositoryBackupResource.class);
		element = router.attach("/management/status", StatusResource.class);
		element = router.attach("/management/solr", SolrResource.class);
//		element = router.attach("/register", RegisterResource.class); // TODO move to /management
		element = router.attach("/{context-id}", ContextResource.class);
		element = router.attach("/{context-id}/sparql", SparqlResource.class);
		element = router.attach("/{context-id}/export", ExportResource.class);
		element = router.attach("/{context-id}/import", ImportResource.class);
		element = router.attach("/{context-id}/statistics/{stat-type}", StatisticsResource.class);
		element = router.attach("/{context-id}/entry/{entry-id}", EntryResource.class);
		element = router.attach("/{context-id}/resource/{entry-id}", ResourceResource.class);
		element = router.attach("/{context-id}/metadata/{entry-id}", MetadataResource.class);
		element = router.attach("/{context-id}/cached-external-metadata/{entry-id}", ExternalMetadataResource.class);
		element = router.attach("/{context-id}/harvester", HarvesterResource.class);
		element = router.attach("/{context-id}/backuplist", ContextBackupListResource.class);
		element = router.attach("/{context-id}/backup/{backup-id}", ContextBackupResource.class);
		element = router.attach("/{context-id}/alias", AliasResource.class);
		element = router.attach("/{context-id}/alias/{entry-id}", AliasResource.class);
		element = router.attach("/{context-id}/relation/{entry-id}", RelationResource.class);
		element = router.attach("/{context-id}/quota", QuotaResource.class);

		router.attachDefault(DefaultResource.class);

		Guard guard = null;
		String authScheme = rm.getConfiguration().getString(Settings.SCAM_AUTH_SCHEME);
		if ("cookie".equalsIgnoreCase(authScheme)) {
			// TODO
		} else if ("http-basic".equalsIgnoreCase(authScheme)) {
			guard = new BasicGuard(getContext(), ChallengeScheme.HTTP_BASIC, "SCAM4", rm.getPrincipalManager());			
		} else {
			log.warn("Unknown or unconfigured authentication scheme, using HTTP Basic");
			guard = new BasicGuard(getContext(), ChallengeScheme.HTTP_BASIC, "SCAM4", rm.getPrincipalManager());
		}
		
		if (guard == null) {
			log.error("Authentication could not be initialized properly");
			return null;
		}
		
		JSCallbackFilter jsCallback = new JSCallbackFilter();
		
		guard.setNext(jsCallback);
		jsCallback.setNext(router);

		return guard;
	}

	/**
	 * This method exists for running stand-alone within Jetty, e.g. Tomcat -
	 * no container required.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		Component component = new Component();
		component.getServers().add(Protocol.HTTP, 8181);
		component.getClients().add(Protocol.FILE);
		Context childContext = component.getContext().createChildContext();
		ScamApplication scamApp = new ScamApplication(childContext);
		childContext.getAttributes().put(KEY, scamApp);
		component.getDefaultHost().attach(scamApp);

		try {
			component.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Gets the {@link ContextManager}.
	 * @return the current {@link ContextManager} for the contexts.
	 */
	public ContextManager getCM() {
		return this.cm; 
	}

	/**
	 * Gets the {@link PrincipalManager}.
	 * @return the current {@link PrincipalManager} for the contexts.
	 */
	public PrincipalManager getPM() {
		return this.pm;
	}

	/**
	 * Gets The current {@link RepositoryManager}.
	 * @return the current {@link RepositoryManager}.
	 */
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
					
					FAOHarvesterFactory faoHF = new FAOHarvesterFactory();
					if (faoHF.isFAOHarvester(entry)) {
						try {
							Harvester h = faoHF.getHarvester(rm, entry.getEntryURI());
							h.run();
							harvesters.add(h);
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
		log.info("Shutting down SCAM");
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