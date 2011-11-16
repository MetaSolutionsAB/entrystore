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

import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.resource.Representation;
import org.restlet.resource.Resource;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;


/**
 * If no other resource can take the URL pattern this class takes it.
 * @author Eric Johansson (eric.johansson@educ.umu.se) 
 * @see Resource
 */
public class DefaultResource extends Resource {

	/**
	 * Constructor.
	 */
	public DefaultResource(Context context, Request request, Response response) {
		super(context, request, response);
		getVariants().add(new Variant(MediaType.TEXT_PLAIN));
	}

	/**
	 * GET
	 * 
	 * prints a message to the client.
	 */
	public Representation represent(Variant variant) throws ResourceException {
			return new JsonRepresentation("{\"info\":\"You requested SCAM v4.0 REST-layer. There is no resource on this URI\"}"); 
	}
}
