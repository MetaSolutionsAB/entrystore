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

package org.entrystore.rest.resources;

import java.net.URI;

import org.entrystore.repository.AuthorizationException;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author Hannes Ebner
 */
public class StatusResource extends BaseResource  {

	static Logger log = LoggerFactory.getLogger(StatusResource.class);
	
	Config config;
	
	@Override
	public void doInit() {
		config = getRM().getConfiguration();
	}
	
	@Get
	public Representation represent() throws ResourceException {
		try {
			if (parameters.containsKey("extended")) {
				JSONObject result = new JSONObject();
				try {
					PrincipalManager pm = getRM().getPrincipalManager();
					URI currentUser = pm.getAuthenticatedUserURI();

					if (pm.getGuestUser().getURI().equals(currentUser)) {
						result.put("error", "You have to be logged in to see the requested information");
						getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
						return new JsonRepresentation(result);
					}

					result.put("baseURI", getRM().getRepositoryURL().toString());
					result.put("repositoryType", config.getString(Settings.STORE_TYPE, "unconfigured"));
					result.put("repositoryIndices", config.getString(Settings.STORE_INDEXES, "unconfigured"));
					result.put("repositoryCache", config.getString(Settings.REPOSITORY_CACHE, "off"));
					result.put("quota", config.getString(Settings.DATA_QUOTA, "off"));
					result.put("quotaDefault", config.getString(Settings.DATA_QUOTA_DEFAULT, "unconfigured"));
					result.put("solr", config.getString(Settings.SOLR, "off"));
					result.put("solrReindexOnStartup", config.getString(Settings.SOLR_REINDEX_ON_STARTUP, "off"));
					result.put("backup", config.getString(Settings.BACKUP_SCHEDULER, "off"));
					result.put("backupMaintenance", config.getString(Settings.BACKUP_MAINTENANCE, "off"));
					result.put("oaiHarvester", config.getString(Settings.HARVESTER_OAI, "off"));
					result.put("oaiHarvesterMultiThreaded", config.getString(Settings.HARVESTER_OAI_MULTITHREADED, "off"));

					if (parameters.containsKey("includeStats")) {
						try {
							pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
							result.put("portfolioCount", getRM().getContextManager().getContextAliases().size());
							result.put("groupCount", pm.getGroupUris().size());
							result.put("userCount", pm.getUsersAsUris().size());
							if (pm.getAdminUser().getURI().equals(currentUser) || pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
								if (getRM() instanceof RepositoryManagerImpl) {
									result.put("namedGraphCount", ((RepositoryManagerImpl) getRM()).getNamedGraphCount());
									result.put("tripleCount", ((RepositoryManagerImpl) getRM()).getTripleCount());
								}
							}
						} finally {
							pm.setAuthenticatedUserURI(currentUser);
						}
					}

					return new JsonRepresentation(result.toString(2));
				} catch (JSONException e) {
					return new JsonRepresentation(result);
				}
			} else {
				if (getRM() != null) {
					return new StringRepresentation("UP", MediaType.TEXT_PLAIN);
				} else {
					return new StringRepresentation("DOWN", MediaType.TEXT_PLAIN);
				}
			}
		} catch (AuthorizationException e) {
			return unauthorizedGET();
		}
	}

}