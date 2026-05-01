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

package org.entrystore.impl;

import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DataImplTest extends AbstractCoreTest {

	private Data data;

	@BeforeEach
	@Override
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(true);

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		Context context = (Context) contextEntry.getResource();
		Entry entry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
		data = (Data) entry.getResource();
	}

	@Disabled("To be implemented")
	@Test
	public void testGetData() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testSetData() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testUseData() throws Exception {
		// TODO
	}

	@Disabled("To be implemented")
	@Test
	public void testRemove() throws Exception {
		// TODO
	}

	@Test
	public void testDelete() {
		// Unauthorized caller (guest has no WriteResource on the entry) must be rejected
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		assertThrows(AuthorizationException.class, () -> data.delete());

		// Authorized caller (admin) must pass the auth check; no backing file exists
		// (DATA_FOLDER is not configured), so delete() returns false but must not throw.
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		assertFalse(data.delete());
	}

	@Disabled("To be implemented")
	@Test
	public void testGetDataFile() throws Exception {
		// TODO
	}

}
