/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.service;

import com.google.common.collect.Sets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

	// contextId becomes a URI segment, so restrict to URL-safe characters.
	private static final Pattern VALID_CONTEXT_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
	// name is a human-readable label stored as an RDF literal; allow spaces and dots.
	private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9 ._-]+$");
	private static final int MAX_IDENTIFIER_LENGTH = 64;

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;

	private final UserService userService;
	private final ReservedNamesService reservedNamesService;

	@Value("${entrystore.nonadmin.group-context-creation:false}")
	private boolean nonAdminGroupContextCreation;


	/**
	 * Creates a group with linked context.
	 */
	public Entry createGroup(String contextId,
							 String name) {

		ContextManager cm = repositoryManager.getContextManager();

		URI requestingUserUri = principalManager.getAuthenticatedUserURI();

		// guests are prohibited from using this resource
		if (requestingUserUri == null || principalManager.getGuestUser().getURI().equals(requestingUserUri)) {
			throw new ForbiddenException("Not allowed for not-logged in or a guest user to create a group");
		}

		if (!nonAdminGroupContextCreation) {
			if (!userService.isAdmin(principalManager.getUser(requestingUserUri))) {
				throw new ForbiddenException("Not allowed for not-admin user to create a group");
			}
		}

		// normalize inputs to null when blank
		String groupName = StringUtils.trimToNull(name);
		String groupContextId = StringUtils.trimToNull(contextId);

		// validate inputs BEFORE escalating to admin so a rejected request never runs with admin rights
		if (groupName != null) {
			validateIdentifier(groupName, "name", VALID_NAME, "letters, digits, spaces, dots, hyphens, and underscores");
		}
		if (groupContextId != null) {
			validateIdentifier(groupContextId, "contextId", VALID_CONTEXT_ID, "letters, digits, hyphens, and underscores");
		}

		// we need admin-rights to create groups and contexts
		return PrincipalManagerUtil.runAsAdmin(principalManager, () -> {
			// check whether context or group with desired name already exists
			// and abort execution of request if necessary
			if (groupName != null && (principalManager.getPrincipalEntry(groupName) != null || cm.getContextURI(groupName) != null)) {
				throw new DataConflictException("Requested value of the name parameter: '" + groupName + "' is already used");
			}

			if (groupContextId != null && cm.getContext(groupContextId) != null) {
				throw new DataConflictException("Requested value of the contextId parameter: '" + groupContextId + "' is already used");
			}

			// create entry for new group
			Entry newGroupEntry = cm.getContext("_principals").createResource(null, GraphType.Group, null, null);
			// make the requesting user admin for group
			newGroupEntry.setAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, Sets.newHashSet(requestingUserUri));
			// change creator from admin to requesting user
			newGroupEntry.setCreator(requestingUserUri);

			Group newGroup = (Group) newGroupEntry.getResource();
			// make requesting user a group member
			newGroup.addMember(principalManager.getUser(requestingUserUri));

			if (groupName != null) {
				// set name of the group
				newGroup.setName(groupName);
			}

			// create entry for new context
			Entry newContextEntry = cm.getContext("_contexts").createResource(groupContextId, GraphType.Context, null, null);
			// make the requesting user admin for context
			newContextEntry.setAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, Sets.newHashSet(requestingUserUri));
			// new group gets write access for context
			newContextEntry.setAllowedPrincipalsFor(PrincipalManager.AccessProperty.WriteResource, Sets.newHashSet(newGroupEntry.getResourceURI()));
			// change creator from admin to requesting user
			newContextEntry.setCreator(requestingUserUri);

			Context newContext = (Context) newContextEntry.getResource();

			if (groupName != null) {
				// set name of the new context
				cm.setName(newContextEntry.getEntryURI(), groupName);
			}

			// set the group's home context to the newly created context
			newGroup.setHomeContext(newContext);

			return newGroupEntry;
		});
	}

	private void validateIdentifier(String value, String fieldName, Pattern allowed, String allowedDescription) {
		if (value.length() > MAX_IDENTIFIER_LENGTH) {
			throw new BadRequestException("Parameter '" + fieldName + "' exceeds maximum length of " + MAX_IDENTIFIER_LENGTH + " characters");
		}
		if (!allowed.matcher(value).matches()) {
			throw new BadRequestException("Parameter '" + fieldName + "' must contain only " + allowedDescription);
		}
		if (value.chars().noneMatch(Character::isLetterOrDigit)) {
			throw new BadRequestException("Parameter '" + fieldName + "' must contain at least one letter or digit");
		}
		if (value.startsWith("_")) {
			throw new BadRequestException("Parameter '" + fieldName + "' must not start with an underscore (reserved for system entities)");
		}
		if (reservedNamesService.isReservedName(value.toLowerCase(Locale.ROOT))) {
			throw new BadRequestException("Parameter '" + fieldName + "' is a reserved name");
		}
	}

}
