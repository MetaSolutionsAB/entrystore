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

package org.entrystore.rest.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.configuration.AppStartedListener;
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.EchoProperties;
import org.entrystore.rest.springboot.configuration.InfoAppPropertiesConfiguration;
import org.entrystore.rest.springboot.configuration.SignupWhitelistProperties;
import org.entrystore.rest.springboot.configuration.StatusReportProperties;
import org.entrystore.rest.springboot.model.api.StatusExtendedIncludeEnum;
import org.entrystore.rest.springboot.model.api.StatusExtendedResponse;
import org.entrystore.rest.springboot.model.api.StatusResponse;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.entrystore.rest.springboot.configuration.StatusReportProperties.UNCONFIGURED;

@Service
@RequiredArgsConstructor
public class StatusService {

	private final InfoAppPropertiesConfiguration appConfig;
	private final StatusReportProperties statusProperties;
	private final SignupWhitelistProperties signupWhitelistProperties;
	private final CorsProperties corsProperties;
	private final EchoProperties echoProperties;

	private final RepositoryManagerImpl repositoryManager;
	private final AppStartedListener appStartedListener;
	private final RelationService relationService;
	private final Optional<BackupScheduler> backupScheduler;


	public boolean isUp() {
		return repositoryManager != null &&
			repositoryManager.getIndex() != null &&
			repositoryManager.getIndex().isUp();
	}

	public StatusResponse getStatus() {
		return new StatusResponse(
			appConfig.app().version(),
			repositoryManager != null ? "online" : "offline");
	}

	public StatusExtendedResponse getStatusExtended(List<StatusExtendedIncludeEnum> includeFields) {

		StatusExtendedResponse.StatusExtendedResponseBuilder builder = StatusExtendedResponse
			.fromStatusResponse(getStatus())
			.baseURI(repositoryManager.getRepositoryURL().toString())
			.rowstoreURL(statusProperties.rowstoreUrl())
			.startupTime(appStartedListener.getStartupTime().atZone(ZoneId.systemDefault()).toString())
			.repositoryType(statusProperties.repositoryType())
			.repositoryIndices(statusProperties.repositoryIndices())
			.quota(statusProperties.quota())
			.quotaDefault(statusProperties.quotaDefault())
			// Reported from the same bean EchoService enforces, so the report cannot disagree with the
			// cap actually applied.
			.echoMaxEntitySize(echoProperties.maxFileSize().toBytes())
			.cors(buildCorsInfo())
			.oaiHarvester(statusProperties.oaiHarvester())
			.oaiHarvesterMultiThreaded(statusProperties.oaiHarvesterMultiThreaded())
			.provenance(statusProperties.provenance())
			.auth(buildAuthenticationInfo())
			.solr(buildSolrInfo())
			.jvm(buildJvmInfo())
			.backup(buildBackupInfo());

		if (!CollectionUtils.isEmpty(includeFields)) {
			builder
				.countStats(
					includeFields.contains(StatusExtendedIncludeEnum.COUNT_STATS) ? buildCountStats() : null)
				.relationStats(
					!Collections.disjoint(
						includeFields,
						List.of(StatusExtendedIncludeEnum.RELATION_STATS, StatusExtendedIncludeEnum.RELATION_VERBOSE_STATS)
					) ? buildRelationStats(includeFields.contains(StatusExtendedIncludeEnum.RELATION_VERBOSE_STATS)) : null);
		}

		return builder.build();
	}

	// Every value comes from CorsProperties, the same bean EntryStoreCorsConfigurationSource builds its
	// policies from, so the report cannot disagree with the installed policy. Note that
	// entrystore.cors.origins defaults to "*": an unset key reports that effective wildcard rather
	// than claiming nothing is configured.
	private Map<String, Object> buildCorsInfo() {
		return Map.of(
			"enabled", corsProperties.enabled(),
			"headers", valueOrUnconfigured(corsProperties.headers()),
			"maxAge", corsProperties.maxAge() > -1
				? String.valueOf(corsProperties.maxAge())
				: UNCONFIGURED,
			"origins", valueOrUnconfigured(corsProperties.origins()),
			"originsAllowCredentials", valueOrUnconfigured(corsProperties.originsAllowCredentials())
		);
	}

	private static String valueOrUnconfigured(String value) {
		return value.isBlank() ? UNCONFIGURED : value;
	}

	private Map<String, Object> buildAuthenticationInfo() {
		return Map.of(
			"signup", statusProperties.signup(),
			// No null filter needed: SignupWhitelistProperties copies through Map.copyOf, which rejects nulls.
			"signupWhitelist", signupWhitelistProperties.whitelist().values()
				.stream()
				.map(String::toLowerCase)
				.collect(Collectors.toList()),
			"passwordReset", statusProperties.passwordReset(),
			"passwordMaxLength", Password.PASSWORD_MAX_LENGTH
			//"authTokenCount", loginTokenCache.size() // not sure how to get this info in Spring-boot default in-memory session storage
		);
	}

	private Map<String, Object> buildSolrInfo() {
		SolrSearchIndex searchIndex = (SolrSearchIndex) repositoryManager.getIndex();
		return Map.of(
			"enabled", statusProperties.solrEnabled(),
			"reindexOnStartup", statusProperties.solrReindexOnStartup(),
			"status", searchIndex.isUp() ? "online" : "offline",
			"postQueueSize", searchIndex.getPostQueueSize(),
			"deleteQueueSize", searchIndex.getDeleteQueueSize(),
			"indexingContexts", searchIndex.getIndexingContexts()
		);
	}

	private Map<String, Object> buildBackupInfo() {
		return Map.of(
			"active", backupScheduler.isPresent(),
			"format", statusProperties.backupFormat(),
			"maintenance", statusProperties.backupMaintenance(),
			"cronExpression", statusProperties.backupCronExpression(),
			"cronExpressionResolved", backupScheduler.map(BackupScheduler::getCronExpression).orElse(""),
			"maintenanceExpiresAfterDays", statusProperties.backupMaintenanceExpiresAfterDays(),
			"maintenanceLowerLimit", statusProperties.backupMaintenanceLowerLimit(),
			"maintenanceUpperLimit", statusProperties.backupMaintenanceUpperLimit()
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

	private Map<String, Object> buildCountStats() {
		PrincipalManager pm = repositoryManager.getPrincipalManager();
		return PrincipalManagerUtil.runAsAdmin(pm, () -> {
			return Map.of(
				"contextCount", repositoryManager.getContextManager().getEntries().size(),
				"groupCount", pm.getGroupUris().size(),
				"userCount", pm.getUsersAsUris().size(),
				"namedGraphCount", repositoryManager.getNamedGraphCount(),
				"tripleCount", repositoryManager.getTripleCount()
			);
		});
	}

	private Map<String, Object> buildRelationStats(boolean verbose) {
		return relationService.getRelationStats(verbose);
	}
}
