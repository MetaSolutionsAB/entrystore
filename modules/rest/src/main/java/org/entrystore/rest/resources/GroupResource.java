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

import com.google.common.collect.Sets;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.config.Settings;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;


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
	 * @param r Request body
	 */
	@Post
	public void acceptRepresentation(Representation r) throws ResourceException {
		URI requestingUser = getPM().getAuthenticatedUserURI();

		try {
			// guests are prohibited from using this resource
			if (requestingUser == null || getPM().getGuestUser().getURI().equals(requestingUser)) {
				getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
				return;
			}

			if (!getRM().getConfiguration().getBoolean(Settings.NONADMIN_GROUPCONTEXT_CREATION, false)) {
				try {
					// only admin users are allowed to create a group in this case
					if (!getPM().getAdminUser().getURI().equals(requestingUser) &&
							!getPM().getAdminGroup().isMember(getPM().getUser(requestingUser))) {
						getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
						return;
					}
				} catch (AuthorizationException ae) {
					getResponse().setStatus(Status.CLIENT_ERROR_FORBIDDEN);
					return;
				}
			}

			String name = null;
			boolean setName = false;
			// read name, to be used for group and context
			if (parameters.containsKey("name")) {
				name = URLDecoder.decode(parameters.get("name"), StandardCharsets.UTF_8).trim();
				setName = name.length() > 0;
			}

			// we need admin-rights to create groups and contexts
			getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI());

			// check whether context or group with desired name already exists
			// and abort execution of request if necessary
			if (setName && getPM().getPrincipalEntry(name) != null || getCM().getContextURI(name) != null) {
				getResponse().setStatus(Status.CLIENT_ERROR_CONFLICT);
				return;
			}

			// create entry for new group
			Entry newGroupEntry = getCM().getContext("_principals").createResource(null, GraphType.Group, null, null);
			// make the requesting user admin for group
			newGroupEntry.setAllowedPrincipalsFor(AccessProperty.Administer, Sets.newHashSet(requestingUser));
			// change creator from admin to requesting user
			newGroupEntry.setCreator(requestingUser);

			Group newGroup = (Group) newGroupEntry.getResource();
			// make requesting user a group member
			newGroup.addMember(getPM().getUser(requestingUser));

			if (setName) {
				// set name of the group
				newGroup.setName(name);
			}

			// create entry for new context
			Entry newContextEntry = getCM().getContext("_contexts").createResource(null, GraphType.Context, null, null);
			// make the requesting user admin for context
			newContextEntry.setAllowedPrincipalsFor(AccessProperty.Administer, Sets.newHashSet(requestingUser));
			// new group gets write access for context
			newContextEntry.setAllowedPrincipalsFor(AccessProperty.WriteResource, Sets.newHashSet(newGroupEntry.getResourceURI()));
			// change creator from admin to requesting user
			newContextEntry.setCreator(requestingUser);

			Context newContext = (Context) newContextEntry.getResource();

			if (setName) {
				// set name of the new context
				getCM().setName(newContextEntry.getEntryURI(), name);
			}

			// set the group's home context to the newly created context
			newGroup.setHomeContext(newContext);

			// return HTTP 201 with the newly created group as Location-header
			getResponse().setStatus(Status.SUCCESS_CREATED);
			getResponse().setLocationRef(newGroupEntry.getEntryURI().toString());
			getResponse().setEntity(createEmptyRepresentationWithLastModified(newGroupEntry.getModifiedDate()));

			return;
		} finally {
			getPM().setAuthenticatedUserURI(requestingUser);
		}
	}

}