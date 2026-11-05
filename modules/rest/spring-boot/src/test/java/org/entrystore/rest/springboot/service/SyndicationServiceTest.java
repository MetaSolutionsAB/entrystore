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

import org.entrystore.AuthorizationException;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RepositoryManagerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How a feed expands a list hit into its children when the caller may not read all of them. */
@ExtendWith(MockitoExtension.class)
class SyndicationServiceTest {

	private static final String BASE = "http://localhost/store/1/entry/";

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextManager contextManager;

	@Mock
	private PrincipalManager principalManager;

	private SyndicationService service;

	@BeforeEach
	void setUp() {
		lenient().when(repositoryManager.getContextManager()).thenReturn(contextManager);
		lenient().when(repositoryManager.getPrincipalManager()).thenReturn(principalManager);
		service = new SyndicationService(repositoryManager, null);
	}

	@Test
	void skipsAChildTheCallerMayNotReadThatGetEntryReturnedUnchecked() {
		Entry readable = childEntry("readable");
		Entry unreadable = childEntry("unreadable");
		// lenient: the readable child reaches the same method with other arguments
		lenient().doThrow(denied()).when(principalManager)
				.checkAuthenticatedUserAuthorized(unreadable, AccessProperty.ReadMetadata);
		Entry list = listEntry("list", readable, unreadable);

		assertEquals(Set.of(readable), service.feedEntries(list));
	}

	@Test
	void skipsAChildWhoseLookupIsDenied() {
		Entry readable = childEntry("readable");
		URI unreadable = uri("unreadable");
		when(contextManager.getEntry(unreadable)).thenThrow(denied());
		Entry list = listEntry("list", List.of(uri("readable"), unreadable));

		assertEquals(Set.of(readable), service.feedEntries(list));
	}

	@Test
	void addsNothingForANestedListWhoseChildrenTheCallerMayNotRead() {
		Entry readable = childEntry("readable");
		Entry nested = entry("nested", GraphType.List);
		org.entrystore.List nestedResource = mock(org.entrystore.List.class);
		when(nested.getResource()).thenReturn(nestedResource);
		when(nestedResource.getChildren()).thenThrow(denied());
		when(contextManager.getEntry(uri("nested"))).thenReturn(nested);
		Entry list = listEntry("list", nested, readable);

		assertEquals(Set.of(readable), service.feedEntries(list));
	}

	@Test
	void endsAtAListThatContainsItself() {
		Entry readable = childEntry("readable");
		Entry list = listEntry("list", List.of(uri("list"), uri("readable")));
		when(contextManager.getEntry(uri("list"))).thenReturn(list);

		assertEquals(Set.of(readable), service.feedEntries(list));
	}

	private static URI uri(String id) {
		return URI.create(BASE + id);
	}

	private static Entry entry(String id, GraphType graphType) {
		Entry entry = mock(Entry.class);
		lenient().when(entry.getGraphType()).thenReturn(graphType);
		lenient().when(entry.getEntryType()).thenReturn(EntryType.Local);
		lenient().when(entry.getEntryURI()).thenReturn(uri(id));
		return entry;
	}

	/** A non-list entry that {@code getEntry} returns. */
	private Entry childEntry(String id) {
		Entry entry = entry(id, GraphType.None);
		when(contextManager.getEntry(uri(id))).thenReturn(entry);
		return entry;
	}

	private static Entry listEntry(String id, Entry... children) {
		return listEntry(id, Arrays.stream(children).map(Entry::getEntryURI).toList());
	}

	private static Entry listEntry(String id, List<URI> children) {
		Entry list = entry(id, GraphType.List);
		org.entrystore.List resource = mock(org.entrystore.List.class);
		when(list.getResource()).thenReturn(resource);
		when(resource.getChildren()).thenReturn(children);
		return list;
	}

	private static AuthorizationException denied() {
		return new AuthorizationException(null, null, AccessProperty.ReadMetadata);
	}
}
