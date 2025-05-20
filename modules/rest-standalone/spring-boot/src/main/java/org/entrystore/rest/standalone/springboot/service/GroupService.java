package org.entrystore.rest.standalone.springboot.service;

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
import org.entrystore.rest.standalone.springboot.model.exception.DataConflictException;
import org.entrystore.rest.standalone.springboot.model.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;

	private final UserService userService;


	/**
	 * Creates a group with linked context.
	 */
	public Entry createGroup(String contextId,
							 String name) {

		ContextManager cm = repositoryManager.getContextManager();

		URI requestingUserUri = principalManager.getAuthenticatedUserURI();
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

			// read name, to be used for group and context
			boolean setName = false;
			if (name != null) {
				name = name.trim();
				setName = !name.isEmpty();
			}

			// we allow to manually set the context entry ID
			if (contextId != null) {
				contextId = StringUtils.trimToNull(contextId);
			}

			// we need admin-rights to create groups and contexts
			principalManager.setAuthenticatedUserURI(principalManager.getAdminUser().getURI());

			// check whether context or group with desired name already exists
			// and abort execution of request if necessary
			if (setName && principalManager.getPrincipalEntry(name) != null || cm.getContextURI(name) != null) {
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

			if (setName) {
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

			if (setName) {
				// set name of the new context
				cm.setName(newContextEntry.getEntryURI(), name);
			}

			// set the group's home context to the newly created context
			newGroup.setHomeContext(newContext);

			return newGroupEntry;
		} finally {
			principalManager.setAuthenticatedUserURI(requestingUserUri);
		}
	}

}
