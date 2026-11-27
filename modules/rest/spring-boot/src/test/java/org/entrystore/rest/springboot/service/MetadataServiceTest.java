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

import org.eclipse.rdf4j.model.Model;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphEntity;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Provenance;
import org.entrystore.ProvenanceType;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.MaxResourceSize;
import org.entrystore.rest.springboot.configuration.TraversalProperties;
import org.entrystore.rest.springboot.model.api.MetadataType;
import org.entrystore.rest.springboot.model.dto.UnreadRdfBody;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.MethodNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

	private static final String TITLE_TURTLE =
			"<http://localhost:8181/1/resource/5> <http://purl.org/dc/terms/title> \"Title\" .";

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private Entry entry;

	@Mock
	private Metadata metadata;

	private MetadataService service;

	@BeforeEach
	void setUp() {
		service = new MetadataService(repositoryManager, new TraversalProperties(Map.of()), principalManager,
				new MaxResourceSize("1k"));
	}

	@Test
	void setEntryMetadata_withoutWriteMetadataAccess_isDeniedBeforeTheBodyIsOpened() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getLocalMetadata()).thenReturn(metadata);
		doThrow(new AuthorizationException(null, null, AccessProperty.WriteMetadata))
				.when(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteMetadata);

		assertThrows(AuthorizationException.class, () -> service.setEntryMetadata(entry, MetadataType.LOCAL_METADATA,
				new UnreadRdfBody(() -> {
					throw new AssertionError("the request body was opened");
				}, 3, "text/turtle", StandardCharsets.UTF_8), null));

		verify(metadata, never()).setGraph(any());
	}

	@Test
	void setEntryMetadata_withWriteMetadataAccess_replacesTheGraph() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getLocalMetadata()).thenReturn(metadata);

		service.setEntryMetadata(entry, MetadataType.LOCAL_METADATA, turtle(TITLE_TURTLE), null);

		verify(principalManager).checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteMetadata);
		verify(metadata).setGraph(argThat(graph -> graph.size() == 1));
	}

	@Test
	void setEntryMetadata_mergedMetadata_isRejectedBeforeTheAccessCheck() {
		assertThrows(BadRequestException.class, () -> service.setEntryMetadata(entry, MetadataType.MERGED_METADATA,
				turtle(TITLE_TURTLE), null));

		verifyNoInteractions(principalManager);
	}

	@Test
	void setEntryMetadata_entryWithoutLocalMetadata_isRejectedBeforeTheAccessCheck() {
		when(entry.getEntryType()).thenReturn(EntryType.Reference);

		assertThrows(MethodNotAllowedException.class, () -> service.setEntryMetadata(entry,
				MetadataType.LOCAL_METADATA, turtle(TITLE_TURTLE), null));

		verifyNoInteractions(principalManager);
	}

	@Test
	void setEntryMetadata_revision_isRejectedBeforeTheAccessCheck() {
		GraphEntity revision = mock(GraphEntity.class);
		Provenance provenance = mock(Provenance.class);
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getProvenance()).thenReturn(provenance);
		when(provenance.getEntityFor("1", ProvenanceType.Metadata)).thenReturn(revision);

		assertThrows(BadRequestException.class, () -> service.setEntryMetadata(entry, MetadataType.LOCAL_METADATA,
				new UnreadRdfBody(() -> {
					throw new AssertionError("the request body was opened");
				}, 3, "text/turtle", StandardCharsets.UTF_8), "1"));

		verifyNoInteractions(principalManager);
		verify(revision, never()).setGraph(any());
	}

	@Test
	void clearEntryMetadata_setsAnEmptyGraph() {
		when(entry.getEntryType()).thenReturn(EntryType.Local);
		when(entry.getLocalMetadata()).thenReturn(metadata);

		service.clearEntryMetadata(entry, MetadataType.LOCAL_METADATA, null);

		verify(metadata).setGraph(argThat(Model::isEmpty));
	}

	private static UnreadRdfBody turtle(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		return new UnreadRdfBody(() -> new ByteArrayInputStream(bytes), bytes.length, "text/turtle",
				StandardCharsets.UTF_8);
	}
}
