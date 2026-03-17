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

package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.standalone.springboot.model.api.SendMessageRequestBody;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.standalone.springboot.util.Email;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;

	public void sendMessage(SendMessageRequestBody request) {
		if (principalManager.getGuestUser().getURI().equals(principalManager.getAuthenticatedUserURI())) {
			throw new ForbiddenException("Guest account is not allowed to send messages");
		}

		if (request.transport() == null || request.subject() == null || request.to() == null || request.body() == null) {
			throw new BadRequestException("Missing required fields: transport, subject, to, body");
		}

		if (principalManager.getPrincipalEntry(request.to()) == null) {
			log.info("User tried to send message to unknown recipient [{}]", request.to());
			throw new ForbiddenException("Unknown recipient");
		}

		String replyTo = principalManager.getPrincipalName(principalManager.getAuthenticatedUserURI());
		if (replyTo != null && !replyTo.contains("@")) {
			replyTo = null;
		}

		if ("email".equalsIgnoreCase(request.transport())) {
			Email.sendMessage(repositoryManager.getConfiguration(), request.to(), request.subject(), request.body(), null, replyTo);
		} else {
			throw new BadRequestException("Unsupported transport type: " + request.transport());
		}
	}
}
