/*
 * Copyright (c) 2007-2015 MetaSolutions AB
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
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.List;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.ContextImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.JSONErrorMessages;
import org.entrystore.rest.util.RDFJSON;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.repository.RepositoryException;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Set;


/**
 * A resource that allows normal users to create a group with a linked context.
 *
 * This resource is an exception as it does not require admin-rights. This feature
 * needs to be explicitly activated in the configuration (the approach may change
 * in the future).
 * 
 * @author Hannes Ebner
 */
public class GroupResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(GroupResource.class);

	/**
	 * Creates a group with linked context.
	 *
	 * @param
	 */
	@Post
	public void acceptRepresentation(Representation r) throws ResourceException {
		try {
			JSONObject req = new JSONObject(r.getText());

			// TODO if current user is guest -> deny request

			// TODO save the current user uri

			// TODO become admin

			// TODO read parameters group name and context name

			// TODO create group principal

			// TODO make creating user to admin of group

			// TODO make creating user a member of the group

			// TODO create context

			// TODO make creating user to admin of context

			// TODO group gets write access on context resource

			/* TODO set group's home context to newly created context
					- this perhaps requires the introduction of Group.setHomeContext() (see User class)
					- Statement s(<group entry URI>) p(<http://entrystore.org/terms/homeContext>) o(<context resource URI>) is set in groups entry information
			 */

			// TODO set names for group and context

			// TODO make sure that creator is set to the calling user (and not admin)

			// TODO send request response with status 201 that contains location header with URI of new group

		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		} catch (JSONException jsone) {
			log.error(jsone.getMessage());
		} finally {
			// TODO reset logged in user
		}
	}
}