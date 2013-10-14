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

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

import org.entrystore.harvester.Harvester;
import org.entrystore.harvester.factory.HarvesterFactory;
import org.entrystore.harvester.factory.HarvesterFactoryException;
import org.entrystore.harvesting.oaipmh.harvester.factory.OAIHarvesterFactory;
import org.entrystore.repository.BuiltinType;
import org.entrystore.repository.Entry;
import org.entrystore.repository.security.AuthorizationException;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.Put;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HarvesterResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(HarvesterResource.class);
	
	Harvester harvester; 

	@Override
	public void doInit() {
	}

	@Get
	public Representation represent() throws ResourceException {
		if (context != null && harvester == null) {
			harvester = getHarvester();
		}
		try {
			JSONObject jsonObj = new JSONObject(); 
			if (harvester == null) {
				return new JsonRepresentation("No harvester found"); 
			} else {
				try {
					getInformation(jsonObj);
				} catch (JSONException e) {
					log.error(e.getMessage()); 
				}
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

	private void getInformation(JSONObject jsonObj) throws JSONException {
		jsonObj.put("target", harvester.getTarget());
		jsonObj.put("harvesterType", harvester.getName());
		jsonObj.put("metadataType", harvester.getMetadataType());

		if(harvester.getFrom() != null) {
			jsonObj.put("from", harvester.getFrom());
		}

		if(harvester.getUntil() != null) {
			jsonObj.put("until", harvester.getUntil());
		}

		if(harvester.getSet() != null) {
			jsonObj.put("set", harvester.getSet());
		}

		if(harvester.getTimeRegExp() != null) {
			jsonObj.put("timeRegularExpression", harvester.getTimeRegExp()); 
		}
	}

	@Post
	public void acceptRepresentation(Representation r) throws ResourceException {
		if (context != null && harvester == null) {
			harvester = getHarvester();
		}
		try {
			if (harvester != null) {
				getResponse().setEntity(new JsonRepresentation("Harvester exists already"));
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return;
			}

			JSONObject jsonObj = getInputJSON();
			
			if (jsonObj == null) {
				getResponse().setEntity(new JsonRepresentation("Invalid harvester configuration"));
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}
			
			try {
				if (!jsonObj.isNull("timeRegularExpression") && !jsonObj.isNull("target")
						&& !jsonObj.isNull("metadataType") && !jsonObj.isNull("harvesterType")) {
					Set<Entry> entries = getCM().getByResourceURI(context.getURI());

					// TODO fix
					Iterator<Entry> iter = entries.iterator();
					Entry entry = null; 
					while (iter.hasNext()) {
						entry = iter.next();
						if (entry.getBuiltinType() == BuiltinType.Context) {
							break; 
						} else {
							entry = null; 
						}
					}

					if (entry == null) {
						log.error("Unable to find the right entry");
						getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
						return;
					}

					try {
						HarvesterFactory factory = null;
						Harvester harvester = null;
						String harvesterType = jsonObj.getString("harvesterType"); 
						if (harvesterType.equalsIgnoreCase("OAI-PMH")) {
							String set;
							if (jsonObj.isNull("set")) {
								set = null;
							} else {
								set = jsonObj.getString("set");
							}
							factory = new OAIHarvesterFactory();
							harvester = factory.createHarvester(
									jsonObj.getString("target"),
									jsonObj.getString("metadataType"),
									set,
									jsonObj.getString("timeRegularExpression"), 
									getRM(),
									context.getEntry().getEntryURI());
						} else {
							log.error("Harvester type unknown: " + harvesterType);
							getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Harvester type unknown: " + harvesterType);
						}
						
						if (harvester == null) {
							log.error("Unable to create harvester type " + harvesterType);
							getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, "Unable to create harvester type " + harvesterType);
						}
						
						if (!jsonObj.isNull("from")) {
							harvester.setFrom((String)jsonObj.getString("from")); 
						}

						if (!jsonObj.isNull("until")) {
							harvester.setUntil((String)jsonObj.get("until"));
						}
						
						if (!jsonObj.isNull("set")) {
							harvester.setSet((String)jsonObj.get("set"));
						}
						
						harvester.run();
						getHarvesters().add(harvester);
						log.info("Harvester created: " + jsonObj.get("target"));
						getResponse().setStatus(Status.SUCCESS_CREATED);
					} catch (HarvesterFactoryException e) {
						log.error(e.getMessage()); 
					}
				} else {
					log.info("Parameters missing in JSON"); 
					getResponse().setEntity(new JsonRepresentation("Parameters missing in JSON"));
					getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				}
			} catch (JSONException e) {
				log.error(e.getMessage());
			}
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		}
	}

	private JSONObject getInputJSON() {
		JSONObject jsonObj = null; 
		try {
			jsonObj = new JSONObject(getRequest().getEntity().getText());
		} catch (JSONException e) {
			log.error(e.getMessage()); 
			return null;
		} catch (IOException e) {
			log.error(e.getMessage());
			return null; 
		}
		return jsonObj; 
	}

	private Harvester getHarvester() {
		for(Harvester h : getHarvesters()) {
			log.info("Harvester URI: " + h.getOwnerContextURI() + "; Context URI: "+ context.getEntry().getEntryURI());
			if(h.getOwnerContextURI().equals(context.getEntry().getEntryURI())) {
				return h;
			}
		}
		return null;
	}

	@Delete
	public void removeRepresentations() throws ResourceException {
		if (context != null && harvester == null) {
			harvester = getHarvester();
		}
		try {
			if (harvester == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
				return;
			}

			String target = harvester.getTarget();
			String type = harvester.getName();
			if (type.equals("OAI-PMH")) {
				HarvesterFactory factory = new OAIHarvesterFactory();
				factory.deleteHarvester(context.getEntry());
			} else {
				log.error("Unknown harvester: " + type);
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Unknown harvester: " + type);
				return;
			}
			harvester.delete();
			getHarvesters().remove(harvester);
			log.info("Harvester deleted: " + type + ":"  + target);
		} catch (AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	@Put
	public void storeRepresentation(Representation r) throws ResourceException {
		if (context != null && harvester == null) {
			harvester = getHarvester();
		}
		try {
			if (harvester == null) {
				getResponse().setEntity(new JsonRepresentation("Harvester does not exist"));
				getResponse().setStatus(Status.CLIENT_ERROR_EXPECTATION_FAILED);
				return;
			}
			
			JSONObject jsonObj = getInputJSON();
			
			if (jsonObj == null) {
				getResponse().setEntity(new JsonRepresentation("Invalid configuration"));
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			} else if (!jsonObj.isNull("updateRecord")) {
				try {
					String updateRecord = jsonObj.getString("updateRecord"); 
					harvester.run(updateRecord);
					log.info("Harvester updating record: " + updateRecord);
					getResponse().setStatus(Status.SUCCESS_ACCEPTED);
				} catch (JSONException e) {
					log.error(e.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
			} else if (!jsonObj.isNull("action")) {
				try {
					String action = jsonObj.getString("action");
//					if (action.equals("pause")) {
//						harvester.stop();
//						getResponse().setStatus(Status.SUCCESS_ACCEPTED);
//						log.info("Harvester paused: " + harvester.getTarget());
//					} else if (action.equals("resume")) {
//						harvester.start();
//						getResponse().setStatus(Status.SUCCESS_ACCEPTED);
//						log.info("Harvester resumed: " + harvester.getTarget());
//					} else {
						log.info("Received unknown action: " + action);
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Unknown action");
//					}
				} catch (JSONException e) {
					log.error(e.getMessage());
					getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
				}
			}
		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}

}