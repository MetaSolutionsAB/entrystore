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
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.repository.RepositoryException;
import org.restlet.data.MediaType;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.representation.Variant;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;

/**
 * @author Eric Johansson
 * @see BaseResource
 */
public class ContextBackupListResource extends BaseResource {
	
	static Logger log = LoggerFactory.getLogger(ContextBackupListResource.class);

	@Override
	public void doInit() {
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
	}

	@Get
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

	@Post
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

}