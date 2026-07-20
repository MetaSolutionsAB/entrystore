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

package org.entrystore.rest.springboot.util;

import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceJsonSerializerTest {

	@Mock
	private PrincipalManager pm;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private LoginAttemptService loginAttemptService;

	@Mock
	private User user;

	private ResourceJsonSerializer serializer;

	@BeforeEach
	void setUp() {
		serializer = new ResourceJsonSerializer(pm, repositoryManager, loginAttemptService);
		when(user.getCustomProperties()).thenReturn(Map.of());
	}

	@Test
	void serializeResourceUser_lockedOut_includesDisabledUntilAndOmitsDisabled() {
		Instant lockedUntil = Instant.parse("2026-05-11T10:30:00Z");
		when(user.getName()).thenReturn("alice");
		when(loginAttemptService.getLockedUntil("alice")).thenReturn(lockedUntil);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabledUntil"));
		// Round-trip through the JSON wire format to pin the ISO-8601 string clients consume:
		// the serializer stores `Instant` in the JSONObject and relies on Instant.toString()
		// being invoked during JSON serialization, so we must serialize-then-parse to assert it.
		JSONObject roundTripped = new JSONObject(result.toString());
		assertEquals(lockedUntil.toString(), roundTripped.getString("disabledUntil"));
		assertFalse(result.has("disabled"));
	}

	@Test
	void serializeResourceUser_notLockedOut_omitsDisabledUntil() {
		when(user.getName()).thenReturn("bob");
		when(loginAttemptService.getLockedUntil("bob")).thenReturn(null);

		JSONObject result = serializer.serializeResourceUser(user);

		assertFalse(result.has("disabledUntil"));
	}

	@Test
	void serializeResourceUser_looksUpLockoutWithLowercasedUsername() {
		Instant lockedUntilForLowercase = Instant.parse("2026-05-11T10:30:00Z");
		when(user.getName()).thenReturn("Alice");
		// Stub ONLY the lowercase key — a regression dropping .toLowerCase() would call
		// getLockedUntil("Alice"), miss the stub, get the default null, and omit the field.
		when(loginAttemptService.getLockedUntil("alice")).thenReturn(lockedUntilForLowercase);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabledUntil"));
		assertEquals(lockedUntilForLowercase.toString(), new JSONObject(result.toString()).getString("disabledUntil"));
	}

	@Test
	void serializeResourceUser_disabledWithoutLockout_omitsDisabledUntil() {
		when(user.getName()).thenReturn("charlie");
		when(user.isDisabled()).thenReturn(true);
		when(loginAttemptService.getLockedUntil("charlie")).thenReturn(null);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabled"));
		assertEquals(true, result.get("disabled"));
		assertFalse(result.has("disabledUntil"));
	}

	@Test
	void serializeResourceUser_disabledAndLockedOut_includesBothFields() {
		// Guards against a refactor that makes one field's emission depend on the other's
		// absence (e.g., `if (!isDisabled()) checkLockout(...)`).
		Instant lockedUntil = Instant.parse("2026-05-11T10:30:00Z");
		when(user.getName()).thenReturn("dave");
		when(user.isDisabled()).thenReturn(true);
		when(loginAttemptService.getLockedUntil("dave")).thenReturn(lockedUntil);

		JSONObject result = serializer.serializeResourceUser(user);

		assertTrue(result.has("disabled"));
		assertEquals(true, result.get("disabled"));
		assertTrue(result.has("disabledUntil"));
		assertEquals(lockedUntil.toString(), new JSONObject(result.toString()).getString("disabledUntil"));
	}

	@Test
	void serializeResourceList_sortByModified_ordersChildrenAscending() {
		Entry a = mockListChild("a", new Date(3000));
		Entry b = mockListChild("b", new Date(1000));
		Entry c = mockListChild("c", new Date(2000));
		var params = new ResourceJsonSerializer.ListParams("modified", null, null, null, true, 0, 0);

		JSONObject result = serializeList(List.of(a, b, c), params);

		assertEquals(List.of("b", "c", "a"), childEntryIds(result));
	}

	@Test
	void serializeResourceList_over500Children_skipsSortingAndKeepsAllUnsorted() {
		// The 500-children cap skips sorting for performance; the first child would move to the
		// end under ascending modified-sort, so it still being first proves sorting was skipped.
		List<Entry> children = new ArrayList<>();
		children.add(mockListChild("newest", new Date(999_999)));
		for (int i = 0; i < 500; i++) {
			children.add(mockListChild("filler" + i, new Date(i)));
		}
		var params = new ResourceJsonSerializer.ListParams("modified", null, null, null, true, 0, 1);

		JSONObject result = serializeList(children, params);

		assertEquals(List.of("newest"), childEntryIds(result));
		assertEquals(501, result.getJSONArray("allUnsorted").length());
		assertEquals(501, result.getInt("size"));
	}

	@Test
	void serializeResourceList_offsetAndLimit_windowChildren() {
		Entry a = mockListChild("a", new Date(1000));
		Entry b = mockListChild("b", new Date(2000));
		Entry c = mockListChild("c", new Date(3000));
		Entry d = mockListChild("d", new Date(4000));
		var params = new ResourceJsonSerializer.ListParams(null, null, null, null, true, 1, 2);

		JSONObject result = serializeList(List.of(a, b, c, d), params);

		assertEquals(List.of("b", "c"), childEntryIds(result));
		assertEquals(2, result.getInt("limit"));
		assertEquals(1, result.getInt("offset"));
		assertEquals(4, result.getInt("size"));
	}

	@Test
	void serializeResourceList_limitZero_returnsAllChildren() {
		Entry a = mockListChild("a", new Date(1000));
		Entry b = mockListChild("b", new Date(2000));
		Entry c = mockListChild("c", new Date(3000));
		var params = new ResourceJsonSerializer.ListParams(null, null, null, null, true, 0, 0);

		JSONObject result = serializeList(List.of(a, b, c), params);

		assertEquals(List.of("a", "b", "c"), childEntryIds(result));
	}

	@Test
	void serializeResourceList_authorizationExceptionOnMetadata_isSwallowedWithoutNoAccessFlag() {
		Entry child = mockListChild("a", new Date(1000));
		when(child.getLocalMetadata()).thenThrow(new AuthorizationException(null, null, null));
		var params = new ResourceJsonSerializer.ListParams(null, null, null, null, true, 0, 0);

		JSONObject result = serializeList(List.of(child), params);

		JSONObject childJson = result.getJSONArray("children").getJSONObject(0);
		assertFalse(childJson.has("noAccessToMetadata"));
		assertFalse(childJson.has(RepositoryProperties.MD_PATH));
		assertTrue(childJson.has("info"), "entry info outside the metadata try must still be serialized");
	}

	@Test
	void serializeResourceList_alwaysEmitsRightsKeyEvenWhenEmpty() {
		Entry child = mockListChild("a", new Date(1000));
		var params = new ResourceJsonSerializer.ListParams(null, null, null, null, true, 0, 0);

		JSONObject result = serializeList(List.of(child), params);

		JSONObject childJson = result.getJSONArray("children").getJSONObject(0);
		assertTrue(childJson.has("rights"));
		assertEquals(0, childJson.getJSONArray("rights").length());
	}

	/**
	 * Wires a mocked {@link org.entrystore.List} whose children resolve through the list's
	 * context and runs {@code serializeResourceList} with an empty-rights default.
	 */
	private JSONObject serializeList(List<Entry> children, ResourceJsonSerializer.ListParams params) {
		when(repositoryManager.getContextManager()).thenReturn(mock(ContextManager.class));
		lenient().when(pm.getRights(any(Entry.class))).thenReturn(Set.of());

		org.entrystore.List list = mock(org.entrystore.List.class);
		Entry listEntry = mock(Entry.class);
		Context context = mock(Context.class);
		when(list.getEntry()).thenReturn(listEntry);
		when(listEntry.getContext()).thenReturn(context);

		List<URI> childUris = new ArrayList<>();
		for (Entry child : children) {
			String uri = child.getEntryURI().toString();
			String id = uri.substring(uri.lastIndexOf('/') + 1);
			childUris.add(child.getEntryURI());
			lenient().when(context.get(id)).thenReturn(child);
		}
		when(list.getChildren()).thenReturn(childUris);

		return serializer.serializeResourceList(list, params, null);
	}

	private static Entry mockListChild(String id, Date modified) {
		Entry child = mock(Entry.class);
		lenient().when(child.getEntryURI()).thenReturn(URI.create("http://example.com/ctx/entry/" + id));
		lenient().when(child.getModifiedDate()).thenReturn(modified);
		lenient().when(child.getGraphType()).thenReturn(GraphType.None);
		lenient().when(child.getEntryType()).thenReturn(EntryType.Local);
		lenient().when(child.getGraph()).thenReturn(new LinkedHashModel());
		lenient().when(child.getRelations()).thenReturn(null);
		Metadata localMetadata = mock(Metadata.class);
		lenient().when(localMetadata.getGraph()).thenReturn(new LinkedHashModel());
		lenient().when(child.getLocalMetadata()).thenReturn(localMetadata);
		return child;
	}

	private static List<String> childEntryIds(JSONObject result) {
		JSONArray children = result.getJSONArray("children");
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < children.length(); i++) {
			ids.add(children.getJSONObject(i).getString("entryId"));
		}
		return ids;
	}
}
