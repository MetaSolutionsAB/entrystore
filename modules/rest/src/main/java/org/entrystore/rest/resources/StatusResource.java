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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager;
import org.entrystore.config.Config;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.security.Password;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.repository.util.URISplit;
import org.entrystore.rest.EntryStoreApplication;
import org.entrystore.rest.auth.LoginTokenCache;
import org.json.JSONArray;
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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.eclipse.rdf4j.model.util.Values.iri;


/**
 * @author Hannes Ebner
 */
public class StatusResource extends BaseResource  {

	static Logger log = LoggerFactory.getLogger(StatusResource.class);

	Config config;

	List<MediaType> supportedMediaTypes = new ArrayList<>();

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
					result.put("echoMaxEntitySize", EchoResource.MAX_ENTITY_SIZE);
					result.put("oaiHarvester", config.getBoolean(Settings.HARVESTER_OAI, false));
					result.put("oaiHarvesterMultiThreaded", config.getBoolean(Settings.HARVESTER_OAI_MULTITHREADED, false));
					result.put("provenance", config.getBoolean(Settings.REPOSITORY_PROVENANCE, false));
					result.put("quota", config.getBoolean(Settings.DATA_QUOTA, false));
					result.put("quotaDefault", config.getString(Settings.DATA_QUOTA_DEFAULT, "unconfigured"));
					result.put("repositoryCache", config.getBoolean(Settings.REPOSITORY_CACHE, false));
					result.put("repositoryIndices", config.getString(Settings.STORE_INDEXES, "unconfigured"));
					result.put("repositoryStatus", getRM() != null ? "online" : "offline");
					result.put("repositoryType", config.getString(Settings.STORE_TYPE, "unconfigured"));
					result.put("rowstoreURL", config.getString(Settings.ROWSTORE_URL, "unconfigured"));
					result.put("version", EntryStoreApplication.getVersion());
					result.put("startupTime", EntryStoreApplication.getStartupDate());

					// Authentication
					JSONObject auth = new JSONObject();
					auth.put("signup", config.getBoolean(Settings.SIGNUP, false));
					List<String> domainWhitelist = config.getStringList(Settings.SIGNUP_WHITELIST, new ArrayList<>());
					JSONArray signupWhitelist = new JSONArray();
					for (String domain : domainWhitelist) {
						if (domain != null) {
							signupWhitelist.put(domain.toLowerCase());
						}
					}
					auth.put("signupWhitelist", signupWhitelist);
					auth.put("passwordReset", config.getBoolean(Settings.AUTH_PASSWORD_RESET, false));
					auth.put("passwordMaxLength", Password.PASSWORD_MAX_LENGTH);
					LoginTokenCache loginTokenCache = ((EntryStoreApplication) getApplication()).getLoginTokenCache();
					auth.put("authTokenCount", loginTokenCache.size());
					result.put("auth", auth);

					// CORS
					JSONObject cors = new JSONObject();
					cors.put("enabled", config.getBoolean(Settings.CORS, false));
					cors.put("headers", config.getString(Settings.CORS_HEADERS, "unconfigured"));
					cors.put("maxAge", config.getString(Settings.CORS_MAX_AGE, "unconfigured"));
					cors.put("origins", config.getString(Settings.CORS_ORIGINS, "unconfigured"));
					cors.put("originsAllowCredentials", config.getString(Settings.CORS_ORIGINS_ALLOW_CREDENTIALS, "unconfigured"));
					result.put("cors", cors);

					// Solr
					JSONObject solr = new JSONObject();
					SolrSearchIndex searchIndex = (SolrSearchIndex) getRM().getIndex();
					solr.put("enabled", config.getBoolean(Settings.SOLR, false));
					solr.put("reindexOnStartup", config.getBoolean(Settings.SOLR_REINDEX_ON_STARTUP, false));
					solr.put("status", searchIndex.isUp() ? "online" : "offline");
					solr.put("postQueueSize", searchIndex.getPostQueueSize());
					solr.put("deleteQueueSize", searchIndex.getDeleteQueueSize());
					solr.put("indexingContexts", searchIndex.getIndexingContexts());
					result.put("solr", solr);

					// Backup
					JSONObject backup = new JSONObject();
					backup.put("active", config.getBoolean(Settings.BACKUP_SCHEDULER, false));
					backup.put("format", config.getString(Settings.BACKUP_FORMAT, "unconfigured"));
					backup.put("maintenance", config.getBoolean(Settings.BACKUP_MAINTENANCE, false));
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
					jvm.put("gc", getGarbageCollectors());
					result.put("jvm", jvm);

					if (parameters.containsKey("includeStats")) {
						JSONObject stats = new JSONObject();
						try {
							pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
							stats.put("contextCount", getRM().getContextManager().getEntries().size());
							stats.put("groupCount", pm.getGroupUris().size());
							stats.put("userCount", pm.getUsersAsUris().size());
							stats.put("namedGraphCount", getRM().getNamedGraphCount());
							stats.put("tripleCount", getRM().getTripleCount());
						} finally {
							pm.setAuthenticatedUserURI(currentUser);
						}
						result.put("stats", stats);
					}

					if (parameters.containsKey("includeRelationStats")) {
						result.put("relationStats", getRelationStats(parameters.get("includeRelationStats").equalsIgnoreCase("verbose")));
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
					if (getRM() != null && getRM().getIndex() != null && getRM().getIndex().isUp()) {
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

	long getTotalCommittedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getCommitted();
	}

	long getTotalUsedMemory() {
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() +
				ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
	}

	JSONArray getGarbageCollectors() {
		JSONArray result = new JSONArray();
		for (GarbageCollectorMXBean gcMxBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			result.put(gcMxBean.getName());
		}
		return result;
	}

	JSONObject getRelationStats(boolean verbose) {
		Repository repository = getRM().getRepository();
		long checkedRelations = 0;
		Map<String, Long> relationStats = new HashMap<>();
		List<String> relationContextsWithoutEntryContext = new ArrayList<>();
		List<String> statementsWithDanglingObject = new ArrayList<>();

		try (RepositoryConnection rc = repository.getConnection()) {
			try (RepositoryResult<Resource> rr = rc.getContextIDs()) {
				for (Resource context : rr) {
					if (context != null && context.isIRI()) {
						String contextIRIStr = context.toString();
						if (isRelationIRI(contextIRIStr)) {
							// check whether corresponding entry exists
							if (!doesEntryExist(contextIRIStr)) {
								log.warn("No corresponding entry found for relation graph <{}>", contextIRIStr);
								relationContextsWithoutEntryContext.add(contextIRIStr);
								continue;
							}

							// check whether relation target exists
							try (RepositoryConnection rc2 = repository.getConnection()) {
								try (RepositoryResult<Statement> relations = rc2.getStatements(null, null, null, false, context)) {
									for (Statement relation : relations) {
										if (!relation.getObject().isIRI()) {
											log.warn("Statement does not have a resource in object position: {}", relation);
										} else {
											try (RepositoryConnection rc3 = repository.getConnection()) {
												try (RepositoryResult<Statement> targetEntry = rc3.getStatements((Resource) relation.getObject(), null, null, false)) {
													checkedRelations++;
													if (!targetEntry.hasNext()) {
														log.warn("Relation target does not exist: <{}>, statement: {}", relation.getObject(), relation);
														statementsWithDanglingObject.add(relation.toString());
														String predicate = relation.getPredicate().toString();
														if (relationStats.containsKey(predicate)) {
															relationStats.put(predicate, relationStats.get(predicate) + 1);
														} else {
															relationStats.put(predicate, 1L);
														}
													}
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}

		for (Map.Entry<String, Long> entry : relationStats.entrySet()) {
			log.info("{}: {}", entry.getKey(), entry.getValue());
		}

		log.info("Checked relations: {}", checkedRelations);
		log.info("Active relations pointing to non-existing targets: {}", statementsWithDanglingObject.size());
		log.info("Relation contexts without existing entry context: {}", relationContextsWithoutEntryContext.size());

		JSONObject result = new JSONObject();
		result.put("checkedRelationCount", checkedRelations);
		result.put("activeRelationsWithNonExistingTargetPredicateStats", relationStats);
		result.put("activeRelationsWithNonExistingTargetCount", statementsWithDanglingObject.size());
		result.put("relationContextsWithoutEntryContextCount", relationContextsWithoutEntryContext.size());

		if (verbose) {
			result.put("activeRelationsWithNonExistingTarget", new JSONArray(statementsWithDanglingObject));
			result.put("relationContextsWithoutEntryContext", new JSONArray(relationContextsWithoutEntryContext));
		}

		return result;
	}

	private boolean doesEntryExist(String relationIRIStr) {
		IRI entryURI = iri(new URISplit(URI.create(relationIRIStr), getRM().getRepositoryURL()).getMetaMetadataURI().toString());
		try (RepositoryConnection rc = getRM().getRepository().getConnection()) {
			try (RepositoryResult<Statement> entryGraph = rc.getStatements(null, null, null, false, entryURI)) {
				if (entryGraph.hasNext()) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isRelationIRI(String iriStr) {
		return (iriStr.startsWith(getRM().getRepositoryURL().toString()) && iriStr.contains("/relations/"));
	}

}
