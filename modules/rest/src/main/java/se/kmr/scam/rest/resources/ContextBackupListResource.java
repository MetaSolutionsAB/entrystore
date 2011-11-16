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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.repository.RepositoryException;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.rest.util.Util;
/**
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 * @see BaseResource
 */
public class ContextBackupListResource extends BaseResource {
	/** Logger. */
	Logger log = LoggerFactory.getLogger(ContextBackupListResource.class);
	se.kmr.scam.repository.Context context; 
	/** Parameters from the URL. Example: ?scam=umu&shame=kth */
	HashMap<String,String> parameters = null; 

	public ContextBackupListResource(Context context, Request request, Response response) {
		super(context, request, response);


		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		String contextId =(String)getRequest().getAttributes().get("context-id"); 
		String remainingPart = request.getResourceRef().getRemainingPart(); 
		parameters = Util.parseRequest(remainingPart);

		if(getCM() != null) {
			try {
				this.context = getCM().getContext(contextId);  
			} catch (NullPointerException e) {
				// not a context
				this.context = null; 
			}
		}


	}

	@Override
	public boolean allowPost() {
		return true;
	}

	//GET
	@Override
	public Representation represent(Variant variant) throws ResourceException {
		try {
			if(context != null) {
				List<Date> dates = getCM().listBackups(context.getURI()); 
				JSONArray array = new JSONArray(); 
				for(Date d : dates) {
					String timestampStr = new SimpleDateFormat("yyyyMMddHHmmss").format(d);
					array.put(timestampStr); 
				}
				return new JsonRepresentation(array.toString()); 

			}
			return new JsonRepresentation("{\"error\":\"Unknown context.\"}"); 
		} catch(AuthorizationException e) {
			return unauthorizedGET();
		}
	}

	//POST
	@Override
	public void acceptRepresentation(Representation representation) throws ResourceException {
		try {
			if(context != null) {
				try {
					JSONObject obj = new JSONObject(getRequest().getEntity().getText());
					if(obj.isNull("format") == false && obj.getString("format").equals("trig")){
						String backupId = getCM().createBackup(context.getURI()); 
						Representation rep = new JsonRepresentation("{\"backupId\":\""+backupId+"\"}"); 
						rep.setMediaType(MediaType.APPLICATION_JSON); 
						getResponse().setEntity(rep); 
					}
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} 
			}
		} catch(AuthorizationException e) {
			unauthorizedPOST();
		} catch (RepositoryException e) {
			log.error(e.getMessage()); 
		}
	}

	//	DELETE
	@Override
	public void removeRepresentations() throws ResourceException {
		try {

		} catch(AuthorizationException e) {
			unauthorizedDELETE();
		}
	}

	//PUT
	@Override
	public void storeRepresentation(Representation entity) throws ResourceException {
		try {

		} catch(AuthorizationException e) {
			unauthorizedPUT();
		}
	}
}
