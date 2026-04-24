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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.rest.springboot.model.api.ExecutePipelineRequestBody;
import org.entrystore.rest.springboot.model.api.ExecutePipelineResponse;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.DataConflictException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.transforms.Pipeline;
import org.entrystore.transforms.TransformException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

	private final ContextService contextService;
	private final PrincipalManager principalManager;

	public ExecutePipelineResponse execute(String contextId, ExecutePipelineRequestBody body) {
		Context context = contextService.getContextOrThrow(contextId);

		String pipelineUri = body == null ? null : body.pipeline();
		if (pipelineUri == null || pipelineUri.isBlank()) {
			throw new BadRequestException("Request field 'pipeline' is required");
		}
		String sourceUri = body.source();
		if (sourceUri != null && sourceUri.isBlank()) {
			sourceUri = null;
		}

		String contextResourceUri = context.getEntry().getResourceURI().toString();
		if (!pipelineUri.startsWith(contextResourceUri)) {
			throw new BadRequestException("Pipeline entry must belong to the request context");
		}
		if (sourceUri != null && !sourceUri.startsWith(contextResourceUri)) {
			throw new BadRequestException("Source entry must belong to the request context");
		}

		principalManager.checkAuthenticatedUserAuthorized(context.getEntry(), AccessProperty.WriteResource);

		Entry pipelineEntry = context.getByEntryURI(URI.create(pipelineUri));
		if (pipelineEntry == null) {
			throw new InternalServerErrorException("Pipeline entry not found in context '" + contextId + "'");
		}

		Entry sourceEntry = null;
		URI listURI = null;
		if (sourceUri != null) {
			sourceEntry = context.getByEntryURI(URI.create(sourceUri));
			if (sourceEntry == null) {
				throw new InternalServerErrorException("Source entry not found in context '" + contextId + "'");
			}
			if (sourceEntry.getEntryType() != EntryType.Local
					|| sourceEntry.getResourceType() != ResourceType.InformationResource
					|| sourceEntry.getMimetype() == null
					|| !(sourceEntry.getResource() instanceof Data)) {
				throw new DataConflictException("Source entry must be a local information resource with data");
			}
			Set<URI> lists = sourceEntry.getReferringListsInSameContext();
			if (lists.size() == 1) {
				listURI = lists.iterator().next();
			}
		}

		if (pipelineEntry.getGraphType() != GraphType.Pipeline) {
			throw new DataConflictException("Pipeline entry must have GraphType.Pipeline");
		}

		Set<Entry> processed;
		try {
			processed = new Pipeline(pipelineEntry).run(sourceEntry, listURI);
		} catch (IllegalStateException e) {
			throw new BadRequestException("Pipeline execution failed: " + e.getMessage(), e);
		} catch (TransformException e) {
			throw new InternalServerErrorException("Pipeline execution failed", e);
		}

		if (processed == null || processed.isEmpty()) {
			return new ExecutePipelineResponse(List.of());
		}
		List<String> resultUris = processed.stream()
				.map(entry -> entry.getEntryURI().toString())
				.toList();
		return new ExecutePipelineResponse(resultUris);
	}
}
