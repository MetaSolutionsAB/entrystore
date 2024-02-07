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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
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
import net.sf.ehcache.CacheManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.CoreStatus;
import org.apache.solr.core.NodeConfig;
import org.apache.solr.util.SolrVersion;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.RDFWriterFactory;
import org.eclipse.rdf4j.rio.binary.BinaryRDFWriterFactory;
import org.eclipse.rdf4j.rio.nquads.NQuadsWriterFactory;
import org.eclipse.rdf4j.rio.trig.TriGWriterFactory;
import org.eclipse.rdf4j.rio.trix.TriXWriterFactory;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.Quota;
import org.entrystore.SearchIndex;
import org.entrystore.config.Config;
import org.entrystore.impl.converters.ConverterUtil;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.RepositoryListener;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.DataCorrection;
import org.entrystore.repository.util.FileOperations;
import org.entrystore.repository.util.NS;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.repository.util.StringUtils;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryManagerImpl implements RepositoryManager {

	private static final Logger log = LoggerFactory.getLogger(RepositoryManagerImpl.class);

	private Repository repository;

	private ContextManagerImpl contextManager;

	private PrincipalManager principalManager;

	private URL baseURL;

	private boolean checkForAuthorization = true;

	private final ArrayList<String> systemContextAliasList = new ArrayList<>();

	private final static Map<String, RepositoryManagerImpl> instances = Collections.synchronizedMap(new HashMap<>());

	private final Map<String, Class> alias2Class = new HashMap<>();

	private boolean modificationLockout = false;

	private boolean shutdown = false;

	private final Object mutex = new Object();

	private final SoftCache softCache;

	private final Config config;

	private CacheManager cacheManager;

	private boolean quotaEnabled = false;

	private long defaultQuota = Quota.VALUE_UNLIMITED;

	private long maximumFileSize = Quota.VALUE_UNLIMITED;

	//ThreadPoolExecutor listenerExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(15);

	private final Map<RepositoryEvent, Set<RepositoryListener>> repositoryListeners = new EnumMap<>(RepositoryEvent.class);

	private SolrClient solrServer;

	private SolrSearchIndex solrIndex;

	private PublicRepository publicRepository;

	private Repository provenanceRepository;

	static boolean trackDeletedEntries;

	private static String VERSION = null;

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
				checkAndUpgradeNativeStore(path, indexes);
				log.info("Main repository: using Native Store at {} with indexes {}", path, indexes);
				NativeStore store = null;
				if (indexes != null) {
					store = new NativeStore(path, indexes);
				} else {
					store = new NativeStore(path);
				}
				this.repository = new SailRepository(store);
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
				repository.init();
			} catch (RepositoryException e) {
				log.error(e.getMessage());
			}

			if ("on".equalsIgnoreCase(config.getString(Settings.REPOSITORY_PROVENANCE, "off"))) {
				initializeProvenanceRepository();
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

		trackDeletedEntries = config.getBoolean(Settings.REPOSITORY_TRACK_DELETED, false);
		log.info("Tracking of deleted entries is " + (trackDeletedEntries ? "activated" : "deactivated"));
		boolean cleanupDeleted = config.getBoolean(Settings.REPOSITORY_TRACK_DELETED_CLEANUP, false);
		if (cleanupDeleted) {
			DataCorrection.cleanupTrackedDeletedEntries(repository);
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
				checkAndUpgradeNativeStore(path, indexes);
				log.info("Provenance repository: using Native Store at {} with indexes {}", path, indexes);

				NativeStore store = null;
				if (indexes != null) {
					store = new NativeStore(path, indexes);
				} else {
					store = new NativeStore(path);
				}
				this.provenanceRepository = new SailRepository(store);
			}
		}
		try {
			this.provenanceRepository.init();
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

	public Repository getRepository() {
		return this.repository;
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
	 * @param file File to export repository to.
	 */
	public void exportToFile(Repository repo, URI file, boolean gzip, RDFFormat format) {
		RepositoryConnection con = null;
		OutputStream out = null;
		Date before = new Date();
		RDFWriterFactory rdfWriterFactory = null;

		if (RDFFormat.TRIG.equals(format)) {
			rdfWriterFactory = new TriGWriterFactory();
		} else if (RDFFormat.TRIX.equals(format)) {
			rdfWriterFactory = new TriXWriterFactory();
		} else if (RDFFormat.NQUADS.equals(format)) {
			rdfWriterFactory = new NQuadsWriterFactory();
		} else if (RDFFormat.BINARY.equals(format)) {
			rdfWriterFactory = new BinaryRDFWriterFactory();
		} else {
			log.error("RDF format is not supported for backups: " + format);
			return;
		}

		log.info("Exporting repository to " + file);
		try {
			con = repo.getConnection();
			out = Files.newOutputStream(new File(file).toPath());
			if (gzip) {
				out = new GZIPOutputStream(out);
			}
			out = new BufferedOutputStream(out);
			RDFWriter writer = rdfWriterFactory.getWriter(out);
			con.export(writer);
		} catch (RepositoryException | IOException | RDFHandlerException e) {
			log.error(e.getMessage());
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

	@Override
	public void shutdown() {
		synchronized (mutex) {
			if (!shutdown) {
				try {
					log.info("Shutting down Quartz scheduler");
					StdSchedulerFactory.getDefaultScheduler().shutdown();
				} catch (SchedulerException se) {
					log.error("Cannot shutdown Quartz scheduler: " + se.getMessage());
				}
				log.info("Shutting down repository listeners and executor");
				//listenerExecutor.shutdown();
				repositoryListeners.clear();
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
				if (solrServer != null) {
					try {
						solrServer.close();
					} catch (IOException e) {
						log.error("Error when shutting down Solr Server");
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

		boolean reindex = config.getBoolean(Settings.SOLR_REINDEX_ON_STARTUP, false);
		boolean reindexWait = config.getBoolean(Settings.SOLR_REINDEX_ON_STARTUP_WAIT, false);

		// Check whether memory store is configured without persistence and enforce
		// reindexing to avoid inconsistencies between memory store and Solr index
		if (!reindex && "memory".equalsIgnoreCase(config.getString(Settings.STORE_TYPE)) &&	!config.containsKey(Settings.STORE_PATH)) {
			reindex = true;
		}

		String solrURL = config.getString(Settings.SOLR_URL);
		if (solrURL.startsWith("http://") || solrURL.startsWith("https://")) {
			log.info("Using HTTP Solr server at " + solrURL);

			HttpSolrClient.Builder solrClientBuilder = new HttpSolrClient.Builder(solrURL).
					withConnectionTimeout(5000).
					withSocketTimeout(5000).
					allowCompression(true);

			String solrUsername = config.getString(Settings.SOLR_AUTH_USERNAME);
			String solrPassword = config.getString(Settings.SOLR_AUTH_PASSWORD);

			if (solrUsername != null && solrPassword != null) {
				CredentialsProvider provider = new BasicCredentialsProvider();
				UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(solrUsername, solrPassword);
				provider.setCredentials(AuthScope.ANY, credentials);
				HttpClient solrHttpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
				solrClientBuilder.withHttpClient(solrHttpClient);
			}

			HttpSolrClient httpSolrClient = solrClientBuilder.build();
			httpSolrClient.setFollowRedirects(true);
			solrServer = httpSolrClient;
		} else {
			log.info("Using embedded Solr server");
			String coreName = "core1";
			String schemaFileName = "SCHEMA_VERSION";
			String solrFileName = "SOLR_VERSION";
			File solrDir = new File(solrURL);
			File solrSchemaVersionFile = new File(solrDir, schemaFileName);
			File solrVersionFile = new File(solrDir, solrFileName);

			// we remove only files if we actually find a version file in the folder as we don't want
			// to risk removing the wrong files because of a misconfigured Solr path
			if (solrSchemaVersionFile.isFile()) {
				String schemaVersion = null;
				try {
					schemaVersion = IOUtils.toString(solrSchemaVersionFile.toURI(), StandardCharsets.UTF_8);
					schemaVersion = schemaVersion.replace("\n", "");
					schemaVersion = schemaVersion.replace("\r", "");
				} catch (IOException e) {
					log.error(e.getMessage());
				}
				String solrVersion = null;
				try {
					solrVersion = IOUtils.toString(solrVersionFile.toURI(), StandardCharsets.UTF_8);
					solrVersion = solrVersion.replace("\n", "");
					solrVersion = solrVersion.replace("\r", "");
				} catch (IOException e) {
					log.error(e.getMessage());
				}

				boolean solrVersionMismatch = true;
				if (solrVersion != null) {
					SolrVersion solrVersionOfIndex = SolrVersion.valueOf(solrVersion);
					if (SolrVersion.LATEST.getMajorVersion() == solrVersionOfIndex.getMajorVersion() &&
							SolrVersion.LATEST.getMinorVersion() == solrVersionOfIndex.getMinorVersion()) {
						solrVersionMismatch = false;
					}
				}

				if (!getVersion().equals(schemaVersion) || solrVersionMismatch) {
					if (solrVersion == null) {
						solrVersion = "<unknown>";
					}
					log.warn("Solr index was created with: EntryStore {} (running version is {}) and Solr {} (running version is {})", schemaVersion, getVersion(), solrVersion,
							SolrVersion.LATEST);
					log.warn("Deleting contents of Solr directory at {} to trigger a clean reindex with current Solr schema", solrDir);
					try {
						FileUtils.cleanDirectory(solrDir);
					} catch (IOException e) {
						log.error(e.getMessage());
					}
				}
			}

			if (solrDir.list() != null && solrDir.list().length == 0) {
				log.info("Solr directory is empty, scheduling conditional reindexing of repository");
				reindex = true;
				reindexWait = true;
				log.info("Writing EntryStore version to schema version file at {}", solrSchemaVersionFile);
				FileOperations.writeStringToFile(solrSchemaVersionFile, getVersion());
				log.info("Writing Solr version to version file at {}", solrVersionFile);
				FileOperations.writeStringToFile(solrVersionFile, SolrVersion.LATEST.toString());
			}

			File solrCoreConfDir = new File(new File(solrDir, coreName), "conf");
			if (!solrCoreConfDir.exists()) {
				if (!solrCoreConfDir.mkdirs()) {
					log.warn("Unable to create directory {}", solrCoreConfDir);
				}
			}

			URL solrConfigXmlSource = config.getURL(Settings.SOLR_CONFIG_URL, ConverterUtil.findResource("solrconfig.xml_default"));
			File solrConfigXmlDest = new File(solrCoreConfDir, "solrconfig.xml");
			try {
				log.info("Copying Solr solrconfig.xml from {} to {}", solrConfigXmlSource, solrConfigXmlDest);
				Files.copy(solrConfigXmlSource.openStream(), solrConfigXmlDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				log.error(e.getMessage());
			}

			URL solrSchemaXmlSource = config.getURL(Settings.SOLR_SCHEMA_URL, ConverterUtil.findResource("schema.xml_default"));
			File solrSchemaXmlDest = new File(solrCoreConfDir, "schema.xml");
			try {
				log.info("Copying Solr schema.xml from {} to {}", solrSchemaXmlSource, solrSchemaXmlDest);
				Files.copy(solrSchemaXmlSource.openStream(), solrSchemaXmlDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				log.error(e.getMessage());
			}

			try {
				System.setProperty("solr.install.dir", solrDir.getCanonicalPath());
				NodeConfig config = new NodeConfig.NodeConfigBuilder("embeddedSolrServerNode", solrDir.toPath())
						.setConfigSetBaseDirectory(solrURL)
						.build();
				this.solrServer = new EmbeddedSolrServer(config, coreName);

				try {
					CoreStatus status = CoreAdminRequest.getCoreStatus(coreName, solrServer);
					// This following triggers an exception if the core does not exist,
					// we need this to test for the core's existence
					status.getCoreStartTime();
				} catch (Exception e) {
					log.info("Creating Solr core");
					CoreAdminRequest.Create createRequest = new CoreAdminRequest.Create();
					createRequest.setCoreName(coreName);
					createRequest.setConfigSet("");
					createRequest.process(solrServer);
					reindex = true;
					reindexWait = true;
				}
			} catch (Exception e) {
				log.error("Failed to initialize Solr: " + e.getMessage());
			}
		}
		if (solrServer != null) {
			solrIndex = new SolrSearchIndex(this, solrServer);
			if (reindex) {
				if (reindexWait) {
					solrIndex.clearSolrIndex(solrServer);
					solrIndex.reindexSync(false);
				} else {
					solrIndex.reindex(false);
				}
			}
		} else {
			log.error("Unable to initialize Solr");
			this.shutdown();
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
			registerListener(updater, RepositoryEvent.EntryCreated);
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

			RepositoryListener contextIndexer = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry e)) {
						if (GraphType.Context.equals(e.getGraphType())) {
							solrIndex.submitContextForDelayedReindex(e, eventObject.getUpdatedGraph());
						}
					}
				}
			};
			registerListener(contextIndexer, RepositoryEvent.EntryAclGuestUpdated);
		}
	}

	public SearchIndex getIndex() {
		return this.solrIndex;
	}

	private void registerPublicRepositoryListeners() {
		if (publicRepository != null) {
			RepositoryListener updater = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						publicRepository.enqueue((Entry) eventObject.getSource());
					}
				}
			};
			registerListener(updater, RepositoryEvent.EntryCreated);
			registerListener(updater, RepositoryEvent.EntryUpdated);
			registerListener(updater, RepositoryEvent.MetadataUpdated);
			registerListener(updater, RepositoryEvent.ExternalMetadataUpdated);
			registerListener(updater, RepositoryEvent.ResourceUpdated);

			// delete
			RepositoryListener remover = new RepositoryListener() {
				@Override
				public void repositoryUpdated(RepositoryEventObject eventObject) {
					if ((eventObject.getSource() != null) && (eventObject.getSource() instanceof Entry)) {
						publicRepository.remove((Entry) eventObject.getSource());
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

	protected void checkAndUpgradeNativeStore(File path, String indexes) {
		if (path.exists() && path.isDirectory() && (path.list().length == 0 || new File(path, "nativerdf.ver").exists())) {
			// no upgrade needed
			return;
		}
		long before = new Date().getTime();
		log.warn("Unable to detect version of Native Store at {}, attempting upgrade", path);

		File valuesDat = new File(path, "values.dat");
		if (!valuesDat.exists()) {
			throw new RuntimeException(valuesDat + " not found, is the path of the Native Store (" + path + ") configured properly?");
		}

		File backupPath = new File(path, "backup");
		if (backupPath.exists() && backupPath.isDirectory() && backupPath.list().length > 0) {
			throw new RuntimeException(("Backup path at " + backupPath + " exists and is not empty"));
		}

		if (!backupPath.exists() && !backupPath.mkdirs()) {
			throw new RuntimeException("Backup path at " + backupPath + " could not be created");
		}

		try {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(path.toPath())) {
				for (Path p : stream) {
					if (!"backup".equals(p.getFileName().toString())) {
						log.debug("Moving " + p + " to " + new File(backupPath, p.getFileName().toString()).toPath());
						Files.move(p, new File(backupPath, p.getFileName().toString()).toPath());
					}
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		SailRepository oldRepo = new SailRepository(new NativeStore(backupPath, indexes));
		oldRepo.init();
		SailRepository newRepo = new SailRepository(new NativeStore(path, indexes));
		newRepo.init();

		long oldTripleCount;
		long newTripleCount;
		long oldContextCount;
		long newContextCount;

		try (RepositoryConnection rcOld = oldRepo.getConnection()) {
			try (RepositoryConnection rcNew = newRepo.getConnection()) {
				rcNew.add(rcOld.getStatements(null, null, null, false));
				oldTripleCount = rcOld.size();
				newTripleCount = rcNew.size();
				oldContextCount = rcOld.getContextIDs().stream().count();
				newContextCount = rcNew.getContextIDs().stream().count();
			} finally {
				newRepo.shutDown();
			}
		} finally {
			oldRepo.shutDown();
		}

		if (oldTripleCount != newTripleCount || oldContextCount != newContextCount) {
			throw new RuntimeException("Amount of triples or contexts in migrated Native Store at " + path + " is not the same as in original Native Store");
		}

		log.info("Repository statistics for {}: {} triples, {} named graphs", path, newTripleCount, newContextCount);

		try {
			FileUtils.deleteDirectory(backupPath);
		} catch (IOException e) {
			log.error(e.getMessage());
		}

		log.info("Native store at {} successfully upgraded, took {} ms", path, new Date().getTime() - before);
	}

	public long getNamedGraphCount() {
		try (RepositoryConnection rc = repository.getConnection()) {
			return rc.getContextIDs().stream().count();
		}
	}

	public long getTripleCount() {
		try (RepositoryConnection rc = repository.getConnection()) {
			return rc.size();
		}
	}

	public static String getVersion() {
		if (VERSION == null) {
			URI versionFile = ConfigurationManager.getConfigurationURI("VERSION.txt");
			if (versionFile != null) {
				try {
					log.debug("Reading version number from " + versionFile);
					VERSION = IOUtils.toString(versionFile, StandardCharsets.UTF_8);
					VERSION = VERSION.replace("\n", "");
					VERSION = VERSION.replace("\r", "");
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			if (VERSION == null) {
				VERSION = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
			}
		}
		return VERSION;
	}

}
