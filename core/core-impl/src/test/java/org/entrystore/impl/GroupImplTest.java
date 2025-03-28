/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

package org.entrystore.impl;

import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GroupImplTest extends AbstractCoreTest {

	@BeforeEach
	public void setUp() {
		super.setUpWithoutSuite();
		((ContextImpl) cm).getSoftCache().clear();
	}

	@Test
	public void testCreateGroup() {
		String userName = "TestUser";
		String groupName = "TestGroup";

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		Entry userEntry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(userEntry.getResourceURI(), userName);
		User user = (User) userEntry.getResource();

		Entry groupEntry = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupEntry.getResourceURI(), groupName);
		Group group = (Group) groupEntry.getResource();
		//group.addMember(user);
		List<URI> users = new ArrayList<>();
		users.add(user.getEntry().getEntryURI());
		group.setChildren(users);

		assertEquals(1, group.members().size());
		Entry groupMember = group.members().getFirst().getEntry();
		assertEquals(user.getURI(), groupMember.getResourceURI());
		assertEquals(1, groupMember.getRelations().size());

		User userQueried = (User) pm.getPrincipalEntry(userName).getResource();
		Group groupQueried = (Group) pm.getPrincipalEntry(groupName).getResource();
		assertEquals(1, groupQueried.members().size());
		Entry groupQueriedMember = groupQueried.members().getFirst().getEntry();
		assertEquals(userQueried.getURI(), groupQueriedMember.getResourceURI());
		assertEquals(1, groupQueriedMember.getRelations().size());
	}
}
