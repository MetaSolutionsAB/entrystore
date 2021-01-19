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

package org.entrystore.rest.resources;

import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;


/**
 * @author Hannes Ebner
 */
public class StatusResource extends BaseResource  {

	static Logger log = LoggerFactory.getLogger(StatusResource.class);
	
	Config config;

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.TEXT_PLAIN);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);

		config = getRM().getConfiguration();
	}
	
	@Get
	public Representation represent() throws ResourceException {
		MediaType preferredMediaType = getRequest().getClientInfo().getPreferredMediaType(supportedMediaTypes);
		if (preferredMediaType == null) {
			preferredMediaType = MediaType.TEXT_PLAIN;
		}
		MediaType prefFormat = (format != null) ? format : preferredMediaType;

		try {
			if (parameters.containsKey("extended")) {
				JSONObject result = new JSONObject();
				try {
					PrincipalManager pm = getRM().getPrincipalManager();
					URI currentUser = pm.getAuthenticatedUserURI();

					if (!pm.getAdminUser().getURI().equals(currentUser) &&
							!pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
						getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
						return new EmptyRepresentation();
					}

					result.put("baseURI", getRM().getRepositoryURL().toString());
					result.put("cors", settingToBoolean(Settings.CORS));
					result.put("corsHeaders", config.getString(Settings.CORS_HEADERS, "unconfigured"));
					result.put("corsMaxAge", config.getString(Settings.CORS_MAX_AGE, "unconfigured"));
					result.put("corsOrigins", config.getString(Settings.CORS_ORIGINS, "unconfigured"));
					result.put("echoMaxEntitySize", EchoResource.MAX_ENTITY_SIZE);
					result.put("oaiHarvester", settingToBoolean(Settings.HARVESTER_OAI));
					result.put("oaiHarvesterMultiThreaded", settingToBoolean(Settings.HARVESTER_OAI_MULTITHREADED));
					result.put("provenance", settingToBoolean(Settings.REPOSITORY_PROVENANCE));
					result.put("quota", settingToBoolean(Settings.DATA_QUOTA));
					result.put("quotaDefault", config.getString(Settings.DATA_QUOTA_DEFAULT, "unconfigured"));
					result.put("repositoryCache", settingToBoolean(Settings.REPOSITORY_CACHE));
					result.put("repositoryIndices", config.getString(Settings.STORE_INDEXES, "unconfigured"));
					result.put("repositoryStatus", getRM() != null ? "online" : "offline");
					result.put("repositoryType", config.getString(Settings.STORE_TYPE, "unconfigured"));
					result.put("rowstoreURL", config.getString(Settings.ROWSTORE_URL, "unconfigured"));
					result.put("passwordReset", settingToBoolean(Settings.PASSWORD_RESET));
					result.put("passwordMaxLength", Password.PASSWORD_MAX_LENGTH);
					result.put("solr", settingToBoolean(Settings.SOLR));
					result.put("solrReindexOnStartup", settingToBoolean(Settings.SOLR_REINDEX_ON_STARTUP));
					result.put("solrStatus", getRM().getIndex().ping() ? "online" : "offline");
					result.put("signup", settingToBoolean(Settings.SIGNUP));
					result.put("version", EntryStoreApplication.getVersion());
					result.put("startupTime", EntryStoreApplication.getStartupDate());
					result.put("authTokenCount", LoginTokenCache.getInstance().size());

					// Backup
					JSONObject backup = new JSONObject();
					backup.put("active", settingToBoolean(Settings.BACKUP_SCHEDULER));
					backup.put("format", config.getString(Settings.BACKUP_FORMAT, "unconfigured"));
					backup.put("maintenance", settingToBoolean(Settings.BACKUP_MAINTENANCE));
					backup.put("cronExpression", config.getString(Settings.BACKUP_CRONEXP, config.getString(Settings.BACKUP_TIMEREGEXP_DEPRECATED, "unconfigured")));
					if (BackupScheduler.getInstance(getRM()) != null) {
						backup.put("cronExpressionResolved", BackupScheduler.getInstance(getRM()).getCronExpression());
					}
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

					if (parameters.containsKey("includeStats")) {
						try {
							pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
							result.put("contextCount", getRM().getContextManager().getEntries().size());
							result.put("groupCount", pm.getGroupUris().size());
							result.put("userCount", pm.getUsersAsUris().size());
							if (getRM() instanceof RepositoryManagerImpl) {
								result.put("namedGraphCount", ((RepositoryManagerImpl) getRM()).getNamedGraphCount());
								result.put("tripleCount", ((RepositoryManagerImpl) getRM()).getTripleCount());
							}
						} finally {
							pm.setAuthenticatedUserURI(currentUser);
						}
					}

					return new JsonRepresentation(result);
				} catch (JSONException e) {
					log.error(e.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
					return new EmptyRepresentation();
				}
			} else {
				if (prefFormat.equals(MediaType.APPLICATION_JSON)) {
					try {
						JSONObject result = new JSONObject();
						result.put("version", EntryStoreApplication.getVersion());
						result.put("repositoryStatus", getRM() != null ? "online" : "offline");
						return new JsonRepresentation(result);
					} catch (JSONException e) {
						log.error(e.getMessage());
						getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
						return new EmptyRepresentation();
					}
				} else {
					if (getRM() != null && getRM().getIndex() != null && getRM().getIndex().ping()) {
						return new StringRepresentation("UP", MediaType.TEXT_PLAIN);
					} else {
						return new StringRepresentation("DOWN", MediaType.TEXT_PLAIN);
					}
				}
			}
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	private boolean settingToBoolean(String key) {
		return "on".equalsIgnoreCase(config.getString(key, "off")) ? true : false;
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