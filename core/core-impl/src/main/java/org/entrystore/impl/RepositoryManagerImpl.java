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

import net.sf.ehcache.CacheManager;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.core.CoreContainer;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.Quota;
import org.entrystore.SearchIndex;
import org.entrystore.config.Config;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.repository.util.StringUtils;
import org.openrdf.model.Resource;
import org.openrdf.model.ValueFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.http.HTTPRepository;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.sparql.SPARQLRepository;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.trig.TriGWriterFactory;
import org.openrdf.sail.memory.MemoryStore;
import org.openrdf.sail.nativerdf.NativeStore;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;


public class RepositoryManagerImpl implements RepositoryManager {

	private static Logger log = LoggerFactory.getLogger(RepositoryManagerImpl.class);

	private Repository repository;

	private ContextManagerImpl contextManager;

	private PrincipalManager principalManager;

	private URL baseURL;

	private boolean checkForAuthorization = true;

	private ArrayList<String> systemContextAliasList = new ArrayList<String>();
	
	private static Map<String, RepositoryManagerImpl> instances = Collections.synchronizedMap(new HashMap<String, RepositoryManagerImpl>());

	private Map<String, Class> alias2Class = new HashMap<String, Class>();

	boolean modificationLockout = false;
	
	boolean shutdown = false;
	
	final Object mutex = new Object();
	
	SoftCache softCache;

	Config config;
	
	CacheManager cacheManager;
	
	boolean quotaEnabled = false;
	
	long defaultQuota = Quota.VALUE_UNLIMITED;

	long maximumFileSize = Quota.VALUE_UNLIMITED;
	
	//ThreadPoolExecutor listenerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(15);
	
	private Map<RepositoryEvent, Set<RepositoryListener>> repositoryListeners = new EnumMap<RepositoryEvent, Set<RepositoryListener>>(RepositoryEvent.class);
	
	SolrServer solrServer;
	
	CoreContainer solrCoreContainer;
	
	SolrSearchIndex solrIndex;
	
	PublicRepository publicRepository;

	private Repository provenanceRepository;

	public RepositoryManagerImpl(String baseURL, Config config) {
		System.setProperty("org.openrdf.repository.debug", "true");
		this.config = config;
		String storeType = config.getString(Settings.STORE_TYPE, "memory").trim();
				
		log.info("Store type: " + storeType);
		
		if (storeType.equalsIgnoreCase("memory")) {
			log.info("Using Memory Store");
			if (config.containsKey(Settings.STORE_PATH)) {
				MemoryStore ms = new MemoryStore(new File(config.getURI(Settings.STORE_PATH)));
				ms.setPersist(true);
				ms.setSyncDelay(5000);
				this.repository = new SailRepository(ms);
			} else {
				this.repository = new SailRepository(new MemoryStore());	
			}
		} else if (storeType.equalsIgnoreCase("native")) {
			if (!config.containsKey(Settings.STORE_PATH)) {
				log.error("Incomplete configuration");
				throw new IllegalStateException("Incomplete configuration");
			} else {
				File path = new File(config.getURI(Settings.STORE_PATH));
				String indexes = config.getString(Settings.STORE_INDEXES);
				log.info("Using Native Store at " + path);
				log.info("Indexes: " + indexes);
				NativeStore store = null;
				if (indexes != null) {
					store = new NativeStore(path, indexes);
				} else {
					store = new NativeStore(path);
				}
				if (store != null) {
					this.repository = new SailRepository(store);
				}
			}
		} else if (storeType.equalsIgnoreCase("http")) {
			if (!config.containsKey(Settings.STORE_URL)) {
				log.error("Incomplete configuration");
				throw new IllegalStateException("Incomplete configuration");
			} else {
				String url = config.getString(Settings.STORE_URL);
				String user = config.getString(Settings.STORE_USER);
				String password = config.getString(Settings.STORE_PWD);
				log.info("Using HTTP repository at " + url);
				this.repository = new HTTPRepository(url);
				if (user != null && password != null) {
					((HTTPRepository) this.repository).setUsernameAndPassword(user, password);
				}
			}
		} else if (storeType.equalsIgnoreCase("sparql")) {
			if (!config.containsKey(Settings.STORE_ENDPOINT_QUERY) ||
					!config.containsKey(Settings.STORE_ENDPOINT_UPDATE)) {
				log.error("Incomplete configuration");
				throw new IllegalStateException("Incomplete configuration");
			} else {
				String endpointQuery = config.getString(Settings.STORE_ENDPOINT_QUERY);
				String endpointUpdate = config.getString(Settings.STORE_ENDPOINT_UPDATE);
				String user = config.getString(Settings.STORE_USER);
				String password = config.getString(Settings.STORE_PWD);
				log.info("Using SPARQL repository at " + endpointQuery + ", " + endpointUpdate);
				this.repository = new SPARQLRepository(endpointQuery, endpointUpdate);
				if (user != null && password != null) {
					((SPARQLRepository) this.repository).setUsernameAndPassword(user, password);
				}
			}
		}
		
		if (this.repository == null) {
			log.error("Failed to create SailRepository");
			throw new IllegalStateException("Failed to create SailRepository");
		}
		
		// create soft cache
		softCache = new SoftCache();
		
		if (config.getString(Settings.REPOSITORY_CACHE, "off").equalsIgnoreCase("on")) {
			String cachePath = config.getString(Settings.REPOSITORY_CACHE_PATH);
			if (cachePath != null) {
				System.setProperty("ehcache.disk.store.dir", cachePath);
			} else {
				log.warn("No disk cache directory configured, creating temp directory");
				try {
					File tmpFolder = FileOperations.createTempDirectory("ehcache", null);
					tmpFolder.deleteOnExit();
					System.setProperty("ehcache.disk.store.dir", tmpFolder.getAbsolutePath());
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			cacheManager = new CacheManager();
			log.info("Disk cache activated, using " + cacheManager.getDiskStorePath());
		} else {
			log.info("Disk cache not activated");
		}
		
		quotaEnabled = config.getString(Settings.DATA_QUOTA, "off").equalsIgnoreCase("on");
		if (quotaEnabled) {
			log.info("Context quotas enabled");
			String quotaValue = config.getString(Settings.DATA_QUOTA_DEFAULT);
			if (quotaValue == null) {
				log.info("Quota default set to UNLIMITED");
			} else {
				defaultQuota = StringUtils.convertUnitStringToByteSize(quotaValue);
				log.info("Quota default set to " + defaultQuota + " bytes");
			}
		} else {
			log.info("Context quotas disabled");
		}

		String maxFileSizeValue = config.getString(Settings.DATA_MAX_FILE_SIZE);
		if (maxFileSizeValue == null) {
			log.info("Maximum file size set to UNLIMITED");
		} else {
			maximumFileSize = StringUtils.convertUnitStringToByteSize(maxFileSizeValue);
			log.info("Maximum file size set to " + maxFileSizeValue + " bytes");
		}
		
		setCheckForAuthorization(false);
		try {
			try {
				this.baseURL = new URL(baseURL);
				NS.getMap().put("store", baseURL);
			} catch (MalformedURLException e1) {
                log.error(e1.getMessage());
			}

			systemContextAliasList.add("_contexts");
			systemContextAliasList.add("_principals");
			
			alias2Class.put("_contexts", ContextManagerImpl.class);
			alias2Class.put("_principals", PrincipalManagerImpl.class);

			try {
				repository.initialize();
			} catch (RepositoryException e) {
                log.error(e.getMessage());
			}

			this.initialize();
			
			String baseURI = config.getString(Settings.BASE_URL);
			if (instances.containsKey(baseURI) || instances.containsValue(this)) {
				log.warn("This RepositoryManager instance has already been created, something is wrong");
			} else {
				log.info("Adding RepositoryManager instance to map: " + baseURI + "," + this);
				instances.put(baseURI, this);
			}
		} finally {
			setCheckForAuthorization(true);
		}
		
		if ("on".equalsIgnoreCase(config.getString(Settings.SOLR, "off")) && config.containsKey(Settings.SOLR_URL)) {
			log.info("Initializing Solr");
			initSolr();
			registerSolrListeners();
		}
		
		if ("on".equalsIgnoreCase(config.getString(Settings.REPOSITORY_PUBLIC, "off"))) {
			log.info("Initializing public repository");
			publicRepository = new PublicRepository(this);
			registerPublicRepositoryListeners();
		}

		if ("on".equalsIgnoreCase(config.getString(Settings.REPOSITORY_PROVENANCE, "off"))) {
			initializeProvenanceRepository();
		}

		log.info("Adding shutdown hook");
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				shutdown();
			}
		});
	}

	private void initializeProvenanceRepository() {
		String storeType = config.getString(Settings.REPOSITORY_PROVENANCE_TYPE, "memory").trim();
		log.info("Provenance repository type: " + storeType);

		if (storeType.equalsIgnoreCase("memory")) {
			this.provenanceRepository = new SailRepository(new MemoryStore());
		} else if (storeType.equalsIgnoreCase("native")) {
			if (!config.containsKey(Settings.REPOSITORY_PROVENANCE_PATH)) {
				log.error("Incomplete configuration of provenance repository");
			} else {
				File path = new File(config.getURI(Settings.REPOSITORY_PROVENANCE_PATH));
				String indexes = config.getString(Settings.REPOSITORY_PROVENANCE_INDEXES);

				log.info("Provenance repository path: " + path);
				log.info("Provenance repository indexes: " + indexes);

				NativeStore store = null;
				if (indexes != null) {
					store = new NativeStore(path, indexes);
				} else {
					store = new NativeStore(path);
				}
				if (store != null) {
					this.provenanceRepository = new SailRepository(store);
				}
			}
		}
		try {
			this.provenanceRepository.initialize();
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}
	}

	/**
	 * Init all System Contexts
	 */
	private void initialize() {
		this.contextManager = new ContextManagerImpl(this, repository);
		this.contextManager.initializeSystemEntries();
	}
	
	/**
	 * @return Returns a named instance of RepositoryManagerImpl. The method may
	 *         return null if the RepositoryManager for the given base URI has
	 *         not been created yet.
	 */
	public static RepositoryManagerImpl getInstance(String baseURI) {
		// in a more complex setup (like applications running in several
		// different JVMs) it is safe to use JNDI or JMX instead. For now we
		// should be safe with this parametrized Singleton.
		
		RepositoryManagerImpl rm = instances.get(baseURI);
		if (rm != null) {		
			log.info("Instance found for " + baseURI);
		} else {
			log.info("No instance found for " + baseURI);
		}
		
		return rm;
	}
	
	public PublicRepository getPublicRepository() {
		return this.publicRepository;
	}

	public Repository getProvenanceRepository() {
		return this.provenanceRepository;
	}
	
	/**
	 * Export the whole repository.
	 * 
	 * @param file File where to export repository to.
	 */
	public void exportToFile(URI file, boolean gzip) {
		RepositoryConnection con = null;
		OutputStream out = null;
		Date before = new Date();
		log.info("Exporting repository to " + file);
		try {
			con = this.repository.getConnection();
			out = new FileOutputStream(new File(file));
			if (gzip) {
				out = new GZIPOutputStream(out);
			}
			out = new BufferedOutputStream(out);
			RDFWriter writer = new TriGWriterFactory().getWriter(out);
			con.export(writer);
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		} catch (RDFHandlerException rdfhe) {
			log.error(rdfhe.getMessage());
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (IOException ioe) {
					log.error(ioe.getMessage());
				}
			}
			if (con != null) {
				try {
					con.close();
				} catch (RepositoryException re) {
					log.error(re.getMessage());
				}
			}
		}
		long timeDiff = new Date().getTime() - before.getTime();
		log.info("Export finished after " + timeDiff + " ms");
	}

	public void shutdown() {
		synchronized (mutex) {
			if (!shutdown) {
				try {
					log.info("Shutting down Quartz scheduler");
					StdSchedulerFactory.getDefaultScheduler().shutdown();
				} catch (SchedulerException se) {
					log.error("Cannot shutdown Quartz scheduler: " + se.getMessage());
				}
				if (repositoryListeners != null) {
					log.info("Shutting down repository listeners and executor");
					//listenerExecutor.shutdown();
					repositoryListeners.clear();
				}
				if (softCache != null) {
					softCache.shutdown();
				}
				if (cacheManager != null) {
					log.info("Shutting down EHCache manager");
					cacheManager.shutdown();
				}
				if (solrIndex != null) {
					log.info("Shutting down Solr support");
					solrIndex.shutdown();
				}
				if (solrCoreContainer != null) {
					log.info("Shutting down Solr core container");
					solrCoreContainer.shutdown();
				}
				if (repository != null) {
					log.info("Shutting down Sesame repository");
					try {
						repository.shutDown();
					} catch (RepositoryException re) {
						log.error("Error when shutting down Sesame repository: " + re.getMessage());
						re.printStackTrace();
					}
				}
				if (publicRepository != null) {
					log.info("Shutting down public repository");
					publicRepository.shutdown();
				}
				if (provenanceRepository != null) {
					log.info("Shutting down Sesame provenance repository");
					try {
						provenanceRepository.shutDown();
					} catch (RepositoryException re) {
						log.error("Error when shutting down Sesame provenance repository: " + re.getMessage());
					}
				}

				shutdown = true;
			}
		}
	}

	public ContextManager getContextManager() {
		return this.contextManager;
	}

	public PrincipalManager getPrincipalManager() {
		if (this.principalManager == null) {
			if (this.contextManager != null) {
				this.principalManager = (PrincipalManager) this.contextManager.getContext(systemContextAliasList.get(1));
			}
		}
		return this.principalManager;
	}

	public URL getRepositoryURL() {
		return this.baseURL;
	}

	public boolean isCheckForAuthorization() {
		return checkForAuthorization;
	}

	public void setCheckForAuthorization(boolean checkForAuthorization) {
		this.checkForAuthorization = checkForAuthorization;
	}

	public List<String> getSystemContextAliases() {
		return systemContextAliasList;
	}

	public Class getSystemContextClassForAlias(String alias) {
		return alias2Class.get(alias);
	}
	
	public Class getRegularContextClass() {
		return RegularContext.class;
	}
	
	public boolean hasModificationLockOut() {
		return modificationLockout;
	}
	
	public void setModificationLockOut(boolean lockout) {
		log.info("Lock out set to " + lockout);
		this.modificationLockout = lockout;
	}
	
	public Config getConfiguration() {
		return config;
	}
	
	public SoftCache getSoftCache() {
		return softCache;
	}

	public CacheManager getCacheManager() {
		return cacheManager;
	}

	public long getDefaultQuota() {
		return defaultQuota;
	}

	public boolean hasQuotas() {
		return quotaEnabled;
	}

	public long getMaximumFileSize() {
		return maximumFileSize;
	}

	public void fireRepositoryEvent(RepositoryEventObject eventObject) {
		// because of concurrency problems the events are fired synchronously,
		// not sure whether this event has a negative impact on performance.
		// async-code is commented out.
		synchronized (repositoryListeners) {
			if (repositoryListeners.containsKey(eventObject.getEvent())) {
				for (RepositoryListener repositoryListener : repositoryListeners.get(eventObject.getEvent())) {
					//repositoryListener.setRepositoryEventObject(eventObject);
					//listenerExecutor.execute(repositoryListener);
					repositoryListener.repositoryUpdated(eventObject);
				}
			}
			if (repositoryListeners.containsKey(RepositoryEvent.All)) {
				for (RepositoryListener repositoryListener : repositoryListeners.get(RepositoryEvent.All)) {
					//repositoryListener.setRepositoryEventObject(eventObject);
					//listenerExecutor.execute(repositoryListener);
					repositoryListener.repositoryUpdated(eventObject);
				}
			}
		}
	}

	public void registerListener(RepositoryListener listener, RepositoryEvent event) {
		synchronized (repositoryListeners) {
			Set<RepositoryListener> listeners = repositoryListeners.get(event);
			if (listeners == null) {
				listeners = new HashSet<RepositoryListener>();
			}
			listeners.add(listener);
			repositoryListeners.put(event, listeners);
			log.info("Registered new RepositoryListener: " + listener);
		}
	}

	public void unregisterListener(RepositoryListener listener, RepositoryEvent event) {
		synchronized (repositoryListeners) {
			Set<RepositoryListener> listeners = repositoryListeners.get(event);
			if (listeners != null) {
				listeners.remove(listener);
				repositoryListeners.put(event, listeners);
				log.info("Unregistered RepositoryListener: " + listener);
			}
		}
	}
	
	private void initSolr() {
		log.info("Manually setting property \"javax.xml.parsers.DocumentBuilderFactory\" to \"com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl\"");
		System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");

		boolean reindex = "on".equalsIgnoreCase(config.getString(Settings.SOLR_REINDEX_ON_STARTUP, "off"));

		// Check whether memory store is configured without persistence and enforce
		// reindexing to avoid inconsistencies between memory store and Solr index
		if (!reindex && "memory".equalsIgnoreCase(config.getString(Settings.STORE_TYPE)) &&	!config.containsKey(Settings.STORE_PATH)) {
			reindex = true;
		}
		
		String solrURL = config.getString(Settings.SOLR_URL);
		if (solrURL.startsWith("http://") || solrURL.startsWith("https://")) {
			log.info("Using HTTP Solr server at " + solrURL);
			HttpSolrServer httpSolrServer = new HttpSolrServer(solrURL);
			httpSolrServer.setAllowCompression(true);
			httpSolrServer.setFollowRedirects(true);
			httpSolrServer.setMaxRetries(1);
			httpSolrServer.setConnectionTimeout(5000); // 5 seconds
			solrServer = httpSolrServer;
		} else {
			log.info("Using embedded Solr server");
			File solrDir = new File(solrURL);
			if (solrDir.list() != null && solrDir.list().length == 0) {
				log.info("Solr directory is empty, scheduling conditional reindexing of repository");
				reindex = true;
			}
			try {
				System.setProperty("solr.solr.home", solrURL);
				log.info("solr.solr.home set to " + solrURL);
				URL solrConfig = ConverterUtil.findResource("solrconfig.xml");

				/* pre-Solr 4.4.0
				CoreContainer.Initializer initializer = new CoreContainer.Initializer();
				CoreContainer coreContainer = initializer.initialize();
				solrServer = new EmbeddedSolrServer(coreContainer, "");
				 */

				// the following should be run with a custom Solr-config
				//CoreContainer.createAndLoad(solrURL, new File(solrConfig.getPath()));

				// we create a container with the default configuration
				CoreContainer coreContainer = new CoreContainer(solrURL);
				coreContainer.load();
				solrServer = new EmbeddedSolrServer(coreContainer, "");
			} catch (Exception e) {
				log.error("Failed to initialize Solr: " + e.getMessage());
			}
		}
		if (solrServer != null) {
			solrIndex = new SolrSearchIndex(this, solrServer);
			if (reindex) {
				solrIndex.reindex();
			}
		} else {
			log.error("Unable to initialize Solr, check settings in scam.properties");
		}
	}
	
	private void registerSolrListeners() {
		if (solrServer != null) {
			RepositoryListener updater = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						solrIndex.postEntry((Entry) eventObject.getSource());
					}
				}
			};
			// registerListener(updater, RepositoryEvent.EntryCreated); // to react on created is not needed, as creation implies update
			registerListener(updater, RepositoryEvent.EntryUpdated);
			registerListener(updater, RepositoryEvent.MetadataUpdated);
			registerListener(updater, RepositoryEvent.ExternalMetadataUpdated);
			registerListener(updater, RepositoryEvent.ResourceUpdated);
			
			RepositoryListener remover = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						solrIndex.removeEntry((Entry) eventObject.getSource());
					}
				}
			};
			registerListener(remover, RepositoryEvent.EntryDeleted);
		}
	}
	
	public SearchIndex getIndex() {
		return this.solrIndex;
	}
	
	private void registerPublicRepositoryListeners() {
		if (publicRepository != null) {
			// add
			RepositoryListener adder = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						publicRepository.addEntry((Entry) eventObject.getSource());
					}
				}
			};
			registerListener(adder, RepositoryEvent.EntryCreated);
			
			// update
			RepositoryListener updater = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						publicRepository.updateEntry((Entry) eventObject.getSource());
					}
				}
			};
			registerListener(updater, RepositoryEvent.EntryUpdated);
			registerListener(updater, RepositoryEvent.MetadataUpdated);
			registerListener(updater, RepositoryEvent.ExternalMetadataUpdated);
			registerListener(updater, RepositoryEvent.ResourceUpdated);
			
			// delete
			RepositoryListener remover = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						publicRepository.removeEntry((Entry) eventObject.getSource());
					}
				}
			};
			registerListener(remover, RepositoryEvent.EntryDeleted);
		}
	}
	
	public ValueFactory getValueFactory() {
		if (repository != null) {
			return repository.getValueFactory();
		}
		return null;
	}
	
	public long getNamedGraphCount() {
		long amountNGs = 0;
		RepositoryConnection rc = null;
		try {
			rc = repository.getConnection();
			RepositoryResult<Resource> contextResult = rc.getContextIDs();
			for (; contextResult.hasNext(); contextResult.next()) {
				amountNGs++;
			}
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} finally {
			try {
				rc.close();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return amountNGs;
	}
	
	public long getTripleCount() {
		long amountTriples = 0;
		RepositoryConnection rc = null;
		try {
			rc = repository.getConnection();
			amountTriples = rc.size();
		} catch (RepositoryException re) {
			log.error(re.getMessage());
		} finally {
			try {
				rc.close();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}
		}
		return amountTriples;
	}

}