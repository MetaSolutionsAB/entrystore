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

import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.SearchIndex;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SolrManagementServiceTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private SearchIndex searchIndex;

	@Mock
	private ContextManager contextManager;

	@Mock
	private Entry contextEntry;

	private SolrManagementService service;

	private static final URI CONTEXT_URI = URI.create("http://localhost:8181/_contexts/entry/testCtx");

	@BeforeEach
	void setUp() {
		service = new SolrManagementService(repositoryManager, principalManager);
	}

	@Test
	void reindex_solrDisabled_throws503() {
		when(repositoryManager.getIndex()).thenReturn(null);

		var exception = assertThrows(CustomResponseException.class, () -> service.reindex(null));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
	}

	@Test
	void reindex_solrDisabledWithContext_throws503() {
		when(repositoryManager.getIndex()).thenReturn(null);

		var exception = assertThrows(CustomResponseException.class, () -> service.reindex(CONTEXT_URI));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
	}

	@Test
	void reindex_fullAsNonAdmin_throws403() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(false);

		assertThrows(ForbiddenException.class, () -> service.reindex(null));
	}

	@Test
	void reindex_fullAlreadyIndexing_throws409() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(true);
		when(searchIndex.isIndexing()).thenReturn(true);

		var exception = assertThrows(CustomResponseException.class, () -> service.reindex(null));
		assertEquals(HttpStatus.CONFLICT, exception.getStatus());
	}

	@Test
	void reindex_fullAsAdmin_success() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(principalManager.currentUserIsAdminOrAdminGroup()).thenReturn(true);
		when(searchIndex.isIndexing()).thenReturn(false);

		var result = service.reindex(null);
		assertEquals("Full Solr reindex initiated", result);
	}

	@Test
	void reindex_contextNotFound_throws400() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(repositoryManager.getContextManager()).thenReturn(contextManager);
		when(contextManager.getByEntryURI(CONTEXT_URI)).thenReturn(null);

		assertThrows(BadRequestException.class, () -> service.reindex(CONTEXT_URI));
	}

	@Test
	void reindex_contextAlreadyIndexing_throws409() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(repositoryManager.getContextManager()).thenReturn(contextManager);
		when(contextManager.getByEntryURI(CONTEXT_URI)).thenReturn(contextEntry);
		when(searchIndex.isIndexing(CONTEXT_URI)).thenReturn(true);

		var exception = assertThrows(CustomResponseException.class, () -> service.reindex(CONTEXT_URI));
		assertEquals(HttpStatus.CONFLICT, exception.getStatus());
	}

	@Test
	void reindex_contextWithAccess_success() {
		when(repositoryManager.getIndex()).thenReturn(searchIndex);
		when(repositoryManager.getContextManager()).thenReturn(contextManager);
		when(contextManager.getByEntryURI(CONTEXT_URI)).thenReturn(contextEntry);
		when(searchIndex.isIndexing(CONTEXT_URI)).thenReturn(false);

		var result = service.reindex(CONTEXT_URI);
		assertEquals("Solr reindex initiated for context: " + CONTEXT_URI, result);
	}
}
