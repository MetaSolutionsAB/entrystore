package org.entrystore.rest.standalone.springboot.controller;/*
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

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
import org.entrystore.SearchIndex;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.entrystore.rest.resources.EchoResource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class StatusHandler {

	static Logger log = LoggerFactory.getLogger(StatusHandler.class);

	private final Config config;
	private final PrincipalManager pm;
	private final URL repositoryUrl;
	private final SearchIndex index;
	private final RepositoryManager rm;

	public StatusHandler(RepositoryManager rm) {
		this.rm = rm;
		this.config = rm.getConfiguration();
		this.pm = rm.getPrincipalManager();
		this.repositoryUrl = rm.getRepositoryURL();
		this.index = rm.getIndex();
	}

	public ResponseEntity<String> statusGetJson(boolean extended, boolean includeStats) {
		try {
			if (extended) {
				JSONObject result = new JSONObject();
				try {
					URI currentUser = pm.getAuthenticatedUserURI();

//					if (!pm.getAdminUser().getURI().equals(currentUser) &&
//						!pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
//							return ServerResponse.status(HttpStatus.FORBIDDEN).contentType(APPLICATION_JSON).build();
//					}

					result.put("baseURI", repositoryUrl.toString());
					result.put("cors", config.getBoolean(Settings.CORS, false));
					result.put("corsHeaders", config.getString(Settings.CORS_HEADERS, "unconfigured"));
					result.put("corsMaxAge", config.getString(Settings.CORS_MAX_AGE, "unconfigured"));
					result.put("corsOrigins", config.getString(Settings.CORS_ORIGINS, "unconfigured"));
					result.put("echoMaxEntitySize", EchoResource.MAX_ENTITY_SIZE);
					result.put("oaiHarvester", config.getBoolean(Settings.HARVESTER_OAI, false));
					result.put("oaiHarvesterMultiThreaded", config.getBoolean(Settings.HARVESTER_OAI_MULTITHREADED, false));
					result.put("provenance", config.getBoolean(Settings.REPOSITORY_PROVENANCE, false));
					result.put("quota", config.getBoolean(Settings.DATA_QUOTA, false));
					result.put("quotaDefault", config.getString(Settings.DATA_QUOTA_DEFAULT, "unconfigured"));
					result.put("repositoryCache", config.getBoolean(Settings.REPOSITORY_CACHE, false));
					result.put("repositoryIndices", config.getString(Settings.STORE_INDEXES, "unconfigured"));

					//TODO: BUG! Will fail withh NullPointer before it reaches this
					result.put("repositoryStatus", rm != null ? "online" : "offline");

					result.put("repositoryType", config.getString(Settings.STORE_TYPE, "unconfigured"));
					result.put("rowstoreURL", config.getString(Settings.ROWSTORE_URL, "unconfigured"));
					result.put("solr", config.getBoolean(Settings.SOLR, false));
					result.put("solrReindexOnStartup", config.getBoolean(Settings.SOLR_REINDEX_ON_STARTUP, false));
					result.put("solrStatus", index.ping() ? "online" : "offline");
					result.put("version", EntryStoreApplication.getVersion());
					result.put("startupTime", EntryStoreApplication.getStartupDate());

					// Authentication
					JSONObject auth = new JSONObject();
					auth.put("signup", config.getBoolean(Settings.SIGNUP, false));
					List<String> domainWhitelist = config.getStringList(Settings.SIGNUP_WHITELIST, new ArrayList<String>());
					JSONArray signupWhitelist = new JSONArray();
					for (String domain : domainWhitelist) {
						if (domain != null) {
							signupWhitelist.put(domain.toLowerCase());
						}
					}
					auth.put("signupWhitelist", signupWhitelist);
					auth.put("passwordReset", config.getBoolean(Settings.PASSWORD_RESET, false));
					auth.put("passwordMaxLength", Password.PASSWORD_MAX_LENGTH);
					auth.put("authTokenCount", LoginTokenCache.getInstance().size());
					result.put("auth", auth);

					// Backup
					JSONObject backup = new JSONObject();
					backup.put("active", config.getBoolean(Settings.BACKUP_SCHEDULER, false));
					backup.put("format", config.getString(Settings.BACKUP_FORMAT, "unconfigured"));
					backup.put("maintenance", config.getBoolean(Settings.BACKUP_MAINTENANCE, false));
					backup.put("cronExpression", config.getString(Settings.BACKUP_CRONEXP, config.getString(Settings.BACKUP_TIMEREGEXP_DEPRECATED, "unconfigured")));
//					if (BackupScheduler.getInstance(getRM()) != null) {
//						backup.put("cronExpressionResolved", BackupScheduler.getInstance(getRM()).getCronExpression());
//					}
					backup.put("maintenanceExpiresAfterDays", config.getString(Settings.BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS, "unconfigured"));
					backup.put("maintenanceLowerLimit", config.getString(Settings.BACKUP_MAINTENANCE_LOWER_LIMIT, "unconfigured"));
					backup.put("maintenanceUpperLimit", config.getString(Settings.BACKUP_MAINTENANCE_UPPER_LIMIT, "unconfigured"));
					result.put("backup", backup);

					// JVM
					JSONObject jvm = new JSONObject();
					jvm.put("totalMemory", Runtime.getRuntime().totalMemory());
					jvm.put("freeMemory", Runtime.getRuntime().freeMemory());
					jvm.put("maxMemory", Runtime.getRuntime().maxMemory());
					jvm.put("availableProcessors", Runtime.getRuntime().availableProcessors());
					jvm.put("totalCommittedMemory", getTotalCommittedMemory());
					jvm.put("committedHeap", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
					jvm.put("totalUsedMemory", getTotalUsedMemory());
					jvm.put("usedHeap", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
					result.put("jvm", jvm);

					if (includeStats) {
//						try {
//							pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
//							result.put("contextCount", getRM().getContextManager().getEntries().size());
//							result.put("groupCount", pm.getGroupUris().size());
//							result.put("userCount", pm.getUsersAsUris().size());
//							result.put("namedGraphCount", getRM().getNamedGraphCount());
//							result.put("tripleCount", getRM().getTripleCount());
//						} finally {
//							pm.setAuthenticatedUserURI(currentUser);
//						}
					}

					return ResponseEntity.ok(result.toString());
				} catch (JSONException e) {
					log.error("JSONException", e);
					return ResponseEntity.status(INTERNAL_SERVER_ERROR).build();
				}
			} else {
				try {
					JSONObject result = new JSONObject();
					result.put("version", EntryStoreApplication.getVersion());
					result.put("repositoryStatus", rm != null ? "online" : "offline");
					return ResponseEntity.ok(result.toString());
				} catch (JSONException e) {
					log.error(e.getMessage());
					return ResponseEntity.status(INTERNAL_SERVER_ERROR).build();
				}
			}
		} catch (AuthorizationException e) {
			return ResponseEntity.status(UNAUTHORIZED).build();
		}
	}

	public ResponseEntity<String> statusGetText() {
		if (index != null && index.ping()) {
			return ResponseEntity.ok().contentType(TEXT_PLAIN).body("UP");
		} else {
			return ResponseEntity.status(INTERNAL_SERVER_ERROR).contentType(TEXT_PLAIN).body("DOWN");
		}
	}

	long getTotalCommittedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted();
	}

	long getTotalUsedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
	}
}
