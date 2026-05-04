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
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataImplTest extends AbstractCoreTest {

	@TempDir
	Path tempDataDir;

	private Entry contextEntry;
	private Context context;
	private Entry entry;
	private Data data;

	@BeforeEach
	@Override
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(true);

		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		contextEntry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) contextEntry.getResource();
		entry = context.createResource(null, GraphType.None, ResourceType.InformationResource, null);
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
	public void delete_throwsForGuest() {
		pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
		assertThrows(AuthorizationException.class, () -> data.delete());
	}

	@Test
	public void delete_returnsFalseWhenNoFile() {
		assertFalse(data.delete());
	}

	@Test
	public void delete_doesNotThrowForGrantedUser() {
		Entry mickey = pm.getPrincipalEntry("Mickey");
		entry.addAllowedPrincipalsFor(AccessProperty.WriteResource, mickey.getResourceURI());
		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		assertFalse(data.delete());
	}

	@Test
	public void delete_returnsTrueWhenFileExists() throws Exception {
		rm.getConfiguration().setProperty(Settings.DATA_FOLDER, tempDataDir.toString());
		data.setData(new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8)));
		assertTrue(data.delete());
		assertFalse(data.delete());
	}

	@Test
	public void remove_bypassesAuthCheck() {
		Entry mickey = pm.getPrincipalEntry("Mickey");
		// Mickey can manage the context but has no WriteResource on the data entry itself.
		// remove(RepositoryConnection) must not check entry-level auth — only delete() does.
		contextEntry.addAllowedPrincipalsFor(AccessProperty.WriteResource, mickey.getResourceURI());
		pm.setAuthenticatedUserURI(mickey.getResourceURI());
		assertDoesNotThrow(() -> context.remove(entry.getEntryURI()));
	}

	@Disabled("To be implemented")
	@Test
	public void testGetDataFile() throws Exception {
		// TODO
	}

}
