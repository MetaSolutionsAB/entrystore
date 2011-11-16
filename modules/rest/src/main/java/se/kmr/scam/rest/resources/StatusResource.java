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

package se.kmr.scam.rest.resources;

import java.net.URI;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.StringRepresentation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.RepositoryManager;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.rest.ScamApplication;
import se.kmr.scam.rest.util.Util;

/**
 * @author Hannes Ebner
 */
public class StatusResource extends BaseResource  {

	Logger log = LoggerFactory.getLogger(StatusResource.class);
	
	RepositoryManager rm;
	
	Config config;
	
	private HashMap<String,String> parameters;
	
	public StatusResource(Context context, Request request, Response response) {
		super(context, request, response);
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		ScamApplication scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);
		rm = scamApp.getRM();
		config = rm.getConfiguration();
		parameters = Util.parseRequest(request.getResourceRef().getRemainingPart());
	}
	
	@Override
	public boolean allowGet() {
		return true;
	}

	@Override
	public boolean allowPut() {
		return false;
	}

	@Override
	public boolean allowPost() {
		return false;
	}

	@Override
	public boolean allowDelete() {
		return false;
	}

	@Override
	public Representation represent(Variant variant) throws ResourceException {
		try {
			if (parameters.containsKey("extended")) {
				JSONObject result = new JSONObject();
				try {
					PrincipalManager pm = rm.getPrincipalManager();
					URI currentUser = pm.getAuthenticatedUserURI();

					if (pm.getGuestUser().getURI().equals(currentUser)) {
						result.put("error", "You have to be logged in to see the requested information");
						getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
						return new JsonRepresentation(result);
					}

					result.put("baseURI", rm.getRepositoryURL().toString());
					result.put("repositoryType", config.getString(Settings.SCAM_STORE_TYPE, "unconfigured"));
					result.put("repositoryIndices", config.getString(Settings.SCAM_STORE_INDEXES, "unconfigured"));
					result.put("repositoryCache", config.getString(Settings.SCAM_REPOSITORY_CACHE, "off"));
					result.put("quota", config.getString(Settings.SCAM_DATA_QUOTA, "off"));
					result.put("quotaDefault", config.getString(Settings.SCAM_DATA_QUOTA_DEFAULT, "unconfigured"));
					result.put("solr", config.getString(Settings.SCAM_SOLR, "off"));
					result.put("solrReindexOnStartup", config.getString(Settings.SCAM_SOLR_REINDEX_ON_STARTUP, "off"));
					result.put("backup", config.getString(Settings.SCAM_BACKUP_SCHEDULER, "off"));
					result.put("backupMaintenance", config.getString(Settings.SCAM_BACKUP_MAINTENANCE, "off"));
					result.put("oaiHarvester", config.getString(Settings.SCAM_HARVESTER_OAI, "off"));
					result.put("oaiHarvesterMultiThreaded", config.getString(Settings.SCAM_HARVESTER_OAI_MULTITHREADED, "off"));

					if (parameters.containsKey("includeStats")) {
						try {
							pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
							result.put("portfolioCount", rm.getContextManager().getContextAliases().size());
							result.put("groupCount", pm.getGroupUris().size());
							result.put("userCount", pm.getUsersAsUris().size());
							if (pm.getAdminUser().getURI().equals(currentUser) || pm.getAdminGroup().isMember(pm.getUser(currentUser))) {
								if (rm instanceof RepositoryManagerImpl) {
									result.put("namedGraphCount", ((RepositoryManagerImpl) rm).getNamedGraphCount());
									result.put("tripleCount", ((RepositoryManagerImpl) rm).getTripleCount());
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
				if (rm != null) {
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