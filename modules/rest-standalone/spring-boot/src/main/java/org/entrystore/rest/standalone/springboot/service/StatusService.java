package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.standalone.springboot.configuration.AppStartedListener;
import org.entrystore.rest.standalone.springboot.configuration.InfoAppPropertiesConfiguration;
import org.entrystore.rest.standalone.springboot.model.api.StatusExtendedResponse;
import org.entrystore.rest.standalone.springboot.model.api.StatusResponse;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final static String DEFAULT_VALUE_FOR_NOT_CONFIGURED = "unconfigured";

	private final InfoAppPropertiesConfiguration appConfig;
	private final Config esConfig;

	private final RepositoryManager repositoryManager;

	private final AppStartedListener appStartedListener;


	public StatusResponse getStatus() {
		return new StatusResponse(
			appConfig.app().version(),
			repositoryManager != null ? "online" : "offline");
	}

	public StatusExtendedResponse getStatusExtended() {
		return StatusExtendedResponse
			.fromStatusResponse(getStatus())
			.baseURI(repositoryManager.getRepositoryURL().toString())
			.rowstoreURL(esConfig.getString(Settings.ROWSTORE_URL, DEFAULT_VALUE_FOR_NOT_CONFIGURED))
			.startupTime(appStartedListener.getStartupTime().atZone(ZoneId.systemDefault()).toString())
			.repositoryType(esConfig.getString(Settings.STORE_TYPE, DEFAULT_VALUE_FOR_NOT_CONFIGURED))
			.repositoryIndices(esConfig.getString(Settings.STORE_INDEXES, DEFAULT_VALUE_FOR_NOT_CONFIGURED))
			.repositoryCache(esConfig.getBoolean(Settings.REPOSITORY_CACHE, false))
			.quota(esConfig.getBoolean(Settings.DATA_QUOTA, false))
			.quotaDefault(esConfig.getString(Settings.DATA_QUOTA_DEFAULT, DEFAULT_VALUE_FOR_NOT_CONFIGURED))
			.echoMaxEntitySize(-1)            // TODO: Fix this when migrating EchoResource
			.oaiHarvester(esConfig.getBoolean(Settings.HARVESTER_OAI, false))
			.oaiHarvesterMultiThreaded(esConfig.getBoolean(Settings.HARVESTER_OAI_MULTITHREADED, false))
			.provenance(esConfig.getBoolean(Settings.REPOSITORY_PROVENANCE, false))
			.auth(buildAuthenticationInfo())
			.solr(buildSolrInfo())
			.jvm(buildJvmInfo())
			.backup(buildBackupInfo())
			.build();
	}

	private Map<String, Object> buildAuthenticationInfo() {
		return Map.of(
			"signup", esConfig.getBoolean(Settings.SIGNUP, false),
			"signupWhitelist", esConfig.getStringList(Settings.SIGNUP_WHITELIST, Collections.emptyList())
				.stream()
				.filter(Objects::nonNull)
				.map(String::toLowerCase)
				.collect(Collectors.toList()),
			"passwordReset", esConfig.getBoolean(Settings.AUTH_PASSWORD_RESET, false),
			"passwordMaxLength", Password.PASSWORD_MAX_LENGTH
			//"authTokenCount", loginTokenCache.size() // not sure how to get this info in Spring-boot default in-memory session storage
		);
	}

	private Map<String, Object> buildSolrInfo() {
		SolrSearchIndex searchIndex = (SolrSearchIndex) repositoryManager.getIndex();
		return Map.of(
			"enabled", esConfig.getBoolean(Settings.SOLR, false),
			"reindexOnStartup", esConfig.getBoolean(Settings.SOLR_REINDEX_ON_STARTUP, false),
			"status", searchIndex.isUp() ? "online" : "offline",
			"postQueueSize", searchIndex.getPostQueueSize(),
			"deleteQueueSize", searchIndex.getDeleteQueueSize(),
			"indexingContexts", searchIndex.getIndexingContexts()
		);
	}

	private Map<String, Object> buildBackupInfo() {
		return Map.of(
			"active", esConfig.getBoolean(Settings.BACKUP_SCHEDULER, false),
			"format", esConfig.getString(Settings.BACKUP_FORMAT, DEFAULT_VALUE_FOR_NOT_CONFIGURED),
			"maintenance", esConfig.getBoolean(Settings.BACKUP_MAINTENANCE, false),
			"cronExpression", esConfig.getString(Settings.BACKUP_CRONEXP, esConfig.getString(Settings.BACKUP_TIMEREGEXP_DEPRECATED, DEFAULT_VALUE_FOR_NOT_CONFIGURED)),
			"cronExpressionResolved", Optional.ofNullable(BackupScheduler.getInstance(repositoryManager)).map(BackupScheduler::getCronExpression).orElse(""),
			"maintenanceExpiresAfterDays", esConfig.getString(Settings.BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS, DEFAULT_VALUE_FOR_NOT_CONFIGURED),
			"maintenanceLowerLimit", esConfig.getString(Settings.BACKUP_MAINTENANCE_LOWER_LIMIT, DEFAULT_VALUE_FOR_NOT_CONFIGURED),
			"maintenanceUpperLimit", esConfig.getString(Settings.BACKUP_MAINTENANCE_UPPER_LIMIT, DEFAULT_VALUE_FOR_NOT_CONFIGURED)
		);
	}

	private Map<String, Object> buildJvmInfo() {
		return Map.of(
			"totalMemory", Runtime.getRuntime().totalMemory(),
			"freeMemory", Runtime.getRuntime().freeMemory(),
			"maxMemory", Runtime.getRuntime().maxMemory(),
			"availableProcessors", Runtime.getRuntime().availableProcessors(),
			"totalCommittedMemory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted(),
			"committedHeap", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted(),
			"totalUsedMemory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
			"usedHeap", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
			"gc", ManagementFactory.getGarbageCollectorMXBeans().stream()
				.map(MemoryManagerMXBean::getName)
				.collect(Collectors.toList())
		);
	}
}
