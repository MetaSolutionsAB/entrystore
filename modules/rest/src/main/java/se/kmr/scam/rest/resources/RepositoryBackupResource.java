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

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.Variant;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Put;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.backup.BackupFactory;
import se.kmr.scam.repository.backup.BackupScheduler;
import se.kmr.scam.rest.ScamApplication;

/**
 * @author Hannes Ebner
 */
public class RepositoryBackupResource extends BaseResource  {

	static Logger log = LoggerFactory.getLogger(RepositoryBackupResource.class);
	
	ScamApplication scamApp;
	
	@Override
	public void doInit() {
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);
	}

	@Get
	public Representation represent(Variant variant) throws ResourceException {
		try {
			JSONObject jsonObj = new JSONObject(); 
			try {
				jsonObj = getInformation();
			} catch (JSONException e) {
				log.error(e.getMessage()); 
			}
			
			if (jsonObj == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return new JsonRepresentation("{\"error\":\"No backup information found\"}");
			}
			
			try {
				return new JsonRepresentation(jsonObj.toString(2));
			} catch (JSONException e) {
				return new JsonRepresentation(jsonObj);
			}
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	private JSONObject getInformation() throws JSONException {
		JSONObject result = null;
		BackupScheduler bs = scamApp.getBackupScheduler();
		if (bs != null) {
			result = new JSONObject();
			result.put("timeRegularExpression", bs.getTimeRegularExpression());
			result.put("gzip", bs.hasCompression());
			result.put("maintenance", bs.hasMaintenance());
			result.put("upperLimit", bs.getUpperLimit());
			result.put("lowerLimit", bs.getLowerLimit());
			result.put("expiresAfterDays", bs.getExpiresAfterDays());
		}
		
		return result;
	}

	@Put
	public void storeRepresentation(Representation representation) throws ResourceException {
		try {
			JSONObject jsonObj = getRequestJSON();
			
			if (jsonObj == null) {
				representation = new JsonRepresentation("Invalid backup configuration");
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
			
			if (scamApp.getBackupScheduler() != null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				representation = new JsonRepresentation("{\"error\":\"Backup scheduler exists already, please delete first\"}");
				return;
			}

			try {
				if (!jsonObj.isNull("timeRegularExpression")) {
					String timeRegExp = jsonObj.getString("timeRegularExpression");
					boolean gzip = false;
					boolean maintenance = false;
					int upperLimit = -1;
					int lowerLimit = -1;
					int expiresAfterDays = -1;
					if (!jsonObj.isNull("gzip")) {
						gzip = jsonObj.getBoolean("gzip");
					}
					if (!jsonObj.isNull("maintenance")) {
						maintenance = jsonObj.getBoolean("maintenance");
					}
					if (!jsonObj.isNull("upperLimit")) {
						upperLimit = jsonObj.getInt("upperLimit");
					}
					if (!jsonObj.isNull("lowerLimit")) {
						lowerLimit = jsonObj.getInt("lowerLimit");
					}
					if (!jsonObj.isNull("expiresAfterDays")) {
						expiresAfterDays = jsonObj.getInt("expiresAfterDays");
					}

					BackupFactory bf = new BackupFactory(scamApp.getRM());
					BackupScheduler bs = bf.createBackupScheduler(timeRegExp, gzip, maintenance, upperLimit, lowerLimit, expiresAfterDays);
					scamApp.setBackupScheduler(bs);
					bs.run();					
				} else {
					log.info("Parameters missing in JSON");
					representation = new JsonRepresentation("Parameters missing in JSON");
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				}
			} catch (JSONException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
				return;
			}
		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}

	private JSONObject getRequestJSON() {
		JSONObject jsonObj = null; 
		try {
			jsonObj = new JSONObject(getRequest().getEntity().getText());
		} catch (JSONException e) {
			log.error(e.getMessage());
		} catch (IOException e) {
			log.error(e.getMessage());
		}
		return jsonObj;
	}

	@Delete
	public void removeRepresentations() throws ResourceException {
		try {
			BackupScheduler bs = scamApp.getBackupScheduler();
			if (bs != null) {
				BackupFactory bf = new BackupFactory(scamApp.getRM());
				Entry backupEntry = scamApp.getRM().getContextManager().getEntry(bf.getBackupEntryURI());
				bf.deleteBackupInformation(backupEntry);
				bs.delete();
				scamApp.setBackupScheduler(null);
				getResponse().setStatus(Status.SUCCESS_OK);
			} else {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND, "No backup information found");
			}
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

}