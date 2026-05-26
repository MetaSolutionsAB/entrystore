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
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
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


	/**
	 * Creates a group with linked context.
	 */
	public Entry createGroup(String contextId,
							 String name) {

		ContextManager cm = repositoryManager.getContextManager();

		URI requestingUserUri = principalManager.getAuthenticatedUserURI();
		Throwable primary = null;
		try {
			// guests are prohibited from using this resource
			if (requestingUserUri == null || principalManager.getGuestUser().getURI().equals(requestingUserUri)) {
				throw new UnauthorizedException("Not allowed for not-logged in or a guest user to create a group");
			}

			if (!repositoryManager.getConfiguration().getBoolean(Settings.NONADMIN_GROUPCONTEXT_CREATION, false)) {
				if (!userService.isAdmin(principalManager.getUser(requestingUserUri))) {
					throw new UnauthorizedException("Not allowed for not-admin user to create a group");
				}
			}

			// normalize inputs to null when blank
			name = StringUtils.trimToNull(name);
			contextId = StringUtils.trimToNull(contextId);

			// validate inputs BEFORE escalating to admin so a rejected request never runs with admin rights
			if (name != null) {
				validateIdentifier(name, "name", VALID_NAME, "letters, digits, spaces, dots, hyphens, and underscores");
			}
			if (contextId != null) {
				validateIdentifier(contextId, "contextId", VALID_CONTEXT_ID, "letters, digits, hyphens, and underscores");
			}

			// we need admin-rights to create groups and contexts
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			// check whether context or group with desired name already exists
			// and abort execution of request if necessary
			if (name != null && (principalManager.getPrincipalEntry(name) != null || cm.getContextURI(name) != null)) {
				throw new DataConflictException("Requested value of the name parameter: '" + name + "' is already used");
			}

			if (contextId != null && cm.getContext(contextId) != null) {
				throw new DataConflictException("Requested value of the contextId parameter: '" + contextId + "' is already used");
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

			if (name != null) {
				// set name of the group
				newGroup.setName(name);
			}

			// create entry for new context
			Entry newContextEntry = cm.getContext("_contexts").createResource(contextId, GraphType.Context, null, null);
			// make the requesting user admin for context
			newContextEntry.setAllowedPrincipalsFor(PrincipalManager.AccessProperty.Administer, Sets.newHashSet(requestingUserUri));
			// new group gets write access for context
			newContextEntry.setAllowedPrincipalsFor(PrincipalManager.AccessProperty.WriteResource, Sets.newHashSet(newGroupEntry.getResourceURI()));
			// change creator from admin to requesting user
			newContextEntry.setCreator(requestingUserUri);

			Context newContext = (Context) newContextEntry.getResource();

			if (name != null) {
				// set name of the new context
				cm.setName(newContextEntry.getEntryURI(), name);
			}

			// set the group's home context to the newly created context
			newGroup.setHomeContext(newContext);

			return newGroupEntry;
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			PrincipalManagerUtil.restoreAuthenticatedUserSafely(principalManager, requestingUserUri, primary);
		}
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
