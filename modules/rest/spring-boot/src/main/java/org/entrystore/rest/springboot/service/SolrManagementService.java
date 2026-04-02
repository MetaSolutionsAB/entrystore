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
import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolrManagementService {

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;

	public void reindex(URI contextUri) {
		if (repositoryManager.getIndex() == null) {
			throw new CustomResponseException("Solr search is deactivated", HttpStatus.SERVICE_UNAVAILABLE);
		}

		if (contextUri == null) {
			// Full reindex
			if (!principalManager.currentUserIsAdminOrAdminGroup()) {
				throw new ForbiddenException("Full reindex requires admin privileges");
			}
			repositoryManager.getIndex().reindex(false);
			log.info("Full Solr reindex initiated");
		} else {
			// Context scoped reindex
			Entry contextEntry = repositoryManager.getContextManager().getByEntryURI(contextUri);
			if (contextEntry == null) {
				throw new BadRequestException("No context found for the provided context URI");
			}
			principalManager.checkAuthenticatedUserAuthorized(contextEntry, AccessProperty.Administer);
			repositoryManager.getIndex().reindex(contextUri, false);
			log.info("Solr reindex initiated for context: {}", contextUri);
		}
	}
}
