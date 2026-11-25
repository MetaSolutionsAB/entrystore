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

import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.impl.ListImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.NotImplementedException;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListResourceServiceTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private Entry entry;

	private ListResourceService service;

	@BeforeEach
	void setUp() {
		service = new ListResourceService(repositoryManager);
	}

	@Test
	void deleteChildren_recursive_removesTheTreeInTheBackground() {
		ListImpl list = mock(ListImpl.class);
		when(entry.getResource()).thenReturn(list);
		when(list.removeTree()).thenReturn(true);

		assertTrue(service.deleteChildren(entry, true), "the tree is removed after the response (202)");
	}

	@Test
	void deleteChildren_recursiveOfATreeRemovedBeforeItReturns_finishesAtOnce() {
		ListImpl list = mock(ListImpl.class);
		when(entry.getResource()).thenReturn(list);
		when(list.removeTree()).thenReturn(false);

		assertFalse(service.deleteChildren(entry, true),
				"a context keeping entry names removes the tree at once (204)");
	}

	@Test
	void deleteChildren_notRecursive_emptiesTheListAtOnce() {
		ListImpl list = mock(ListImpl.class);
		when(entry.getResource()).thenReturn(list);

		assertFalse(service.deleteChildren(entry, false), "the list is empty when the response is sent (204)");
		verify(list).setChildren(new java.util.Vector<>());
		verify(list, never()).removeTree();
	}

	@Test
	void setChildrenFromJson_unknownChild_throwsBadRequest() {
		Context context = mock(Context.class);
		when(entry.getContext()).thenReturn(context);
		when(context.get("missing")).thenReturn(null);

		assertThrows(BadRequestException.class,
				() -> service.setChildrenFromJson(entry, "[\"missing\"]".getBytes(StandardCharsets.UTF_8)));

		// Rejected while resolving the ids, before the list resource is touched.
		verify(entry, never()).getResource();
	}

	@Test
	void moveEntry_nonListGraphType_throwsBadRequest() {
		when(entry.getGraphType()).thenReturn(GraphType.None);

		assertThrows(BadRequestException.class,
				() -> service.moveEntry(entry, "1/entry/2", "1/resource/3", false));

		verifyNoInteractions(repositoryManager);
	}

	@Test
	void importFromZip_list_throwsNotImplemented() {
		when(entry.getGraphType()).thenReturn(GraphType.List);

		assertThrows(NotImplementedException.class, () -> service.importFromZip(entry));
	}

	@Test
	void importFromZip_nonListGraphType_throwsBadRequest() {
		when(entry.getGraphType()).thenReturn(GraphType.None);

		assertThrows(BadRequestException.class, () -> service.importFromZip(entry));
	}

	@Test
	void serializeChildrenIds_listWithSort_returnsSortedIdArray() {
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(3000));
		mockResolvableChild(context, "b", new Date(1000));
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeChildrenIds(entry, filter);

		assertEquals(List.of("b", "c", "a"), jsonArrayToList(result));
	}

	@Test
	void serializeChildrenIds_listSortDescending_returnsReversedIdArray() {
		// Pins the !"desc".equalsIgnoreCase(order) mapping in ListParams.withoutPagination.
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(3000));
		mockResolvableChild(context, "b", new Date(1000));
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, "desc", null, null);

		String result = service.serializeChildrenIds(entry, filter);

		assertEquals(List.of("a", "c", "b"), jsonArrayToList(result));
	}

	@Test
	void serializeChildrenIds_listOver500Children_returnsUnsortedIds() {
		// Above 500 children the sort branch is skipped entirely: the raw (HashSet-ordered,
		// nondeterministic) ID set is returned and no child entry is resolved.
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 501; i++) {
			ids.add("id" + i);
		}
		Context context = mockListEntry(ids);
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeChildrenIds(entry, filter);

		List<String> returned = jsonArrayToList(result);
		assertEquals(501, returned.size());
		assertEquals(new HashSet<>(ids), new HashSet<>(returned));
		verify(context, never()).get(anyString());
	}

	@Test
	void moveEntry_absoluteHttpsUris_passedToMoveEntryHereUnchanged() throws Exception {
		// Only http:// used to count as absolute, so HTTPS deployments had their absolute URIs prefixed with the base.
		ListImpl list = mock(ListImpl.class);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("https://host/").toURL());

		service.moveEntry(entry, "https://host/1/entry/2", "https://host/1/resource/3", false);

		verify(list).moveEntryHere(URI.create("https://host/1/entry/2"), URI.create("https://host/1/resource/3"),
				false);
	}

	@Test
	void moveEntry_relativePaths_prefixedWithBaseUrlAndSlash() throws Exception {
		ListImpl list = mock(ListImpl.class);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		// No trailing slash on the base URL: the separator must be inserted.
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("https://host").toURL());

		service.moveEntry(entry, "1/entry/2", "1/resource/3", true);

		verify(list).moveEntryHere(URI.create("https://host/1/entry/2"), URI.create("https://host/1/resource/3"),
				true);
	}

	@Test
	void moveEntry_dataCopyFails_throwsInternalServerError() throws Exception {
		ListImpl list = mock(ListImpl.class);
		when(entry.getGraphType()).thenReturn(GraphType.List);
		when(entry.getResource()).thenReturn(list);
		when(repositoryManager.getRepositoryURL()).thenReturn(URI.create("https://host/").toURL());
		IOException failure = new IOException("No space left on device");
		when(list.moveEntryHere(URI.create("https://host/1/entry/2"), URI.create("https://host/1/resource/3"), true))
				.thenThrow(failure);

		InternalServerErrorException thrown = assertThrows(InternalServerErrorException.class,
				() -> service.moveEntry(entry, "1/entry/2", "1/resource/3", true));

		assertEquals(failure, thrown.getCause());
	}

	@Test
	void serializeChildrenIds_listSortedBranch_missingChildSkipped() {
		// "b" is referenced by the list but does not resolve in the context — it is logged
		// and dropped from the sorted output (unlike the unsorted branch, which keeps raw IDs).
		Context context = mockListEntry(List.of("a", "b", "c"));
		mockResolvableChild(context, "a", new Date(1000));
		when(context.get("b")).thenReturn(null);
		mockResolvableChild(context, "c", new Date(2000));
		var filter = new ListFilter("modified", null, null, null, null, null, null);

		String result = service.serializeChildrenIds(entry, filter);

		assertEquals(List.of("a", "c"), jsonArrayToList(result));
	}

	@Test
	void serializeChildrenIds_listWithSort_ignoresNonNumericOffsetAndLimit() {
		// The sorting path ignores offset/limit, so malformed values must not fail the request
		// (ListParams.withoutPagination skips the Integer.parseInt done by ListParams(ListFilter)).
		Context context = mockListEntry(List.of("a", "b"));
		mockResolvableChild(context, "a", new Date(2000));
		mockResolvableChild(context, "b", new Date(1000));
		var filter = new ListFilter("modified", null, null, null, null, "abc", "xyz");

		String result = service.serializeChildrenIds(entry, filter);

		assertEquals(List.of("b", "a"), jsonArrayToList(result));
	}

	/** Stubs {@code entry} as a List entry whose list resource references the given child IDs. */
	private Context mockListEntry(List<String> childIds) {
		Context context = mock(Context.class);
		org.entrystore.List list = mock(org.entrystore.List.class);
		when(entry.getResource()).thenReturn(list);
		lenient().when(entry.getContext()).thenReturn(context);
		List<URI> childUris = new ArrayList<>();
		for (String id : childIds) {
			childUris.add(URI.create("http://example.com/ctx/entry/" + id));
		}
		when(list.getChildren()).thenReturn(childUris);
		return context;
	}

	private void mockResolvableChild(Context context, String id, Date modified) {
		Entry child = mock(Entry.class);
		lenient().when(child.getEntryURI()).thenReturn(URI.create("http://example.com/ctx/entry/" + id));
		lenient().when(child.getModifiedDate()).thenReturn(modified);
		lenient().when(context.get(id)).thenReturn(child);
	}

	private static List<String> jsonArrayToList(String json) {
		JSONArray array = new JSONArray(json);
		List<String> values = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			values.add(array.getString(i));
		}
		return values;
	}
}
