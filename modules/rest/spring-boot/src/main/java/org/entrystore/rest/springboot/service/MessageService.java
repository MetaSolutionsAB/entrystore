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
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.RepositoryException;
import org.entrystore.rest.springboot.model.api.SendMessageRequestBody;
import org.entrystore.rest.springboot.model.api.TransportType;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.springboot.util.Email;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

	private final PrincipalManager principalManager;
	private final RepositoryManagerImpl repositoryManager;

	public void sendMessage(SendMessageRequestBody request) {
		if (principalManager.currentUserIsGuest()) {
			throw new UnauthorizedException("Not allowed for not-logged in or a guest user to send messages");
		}

		try {
			if (principalManager.getPrincipalEntry(request.recipient()) == null) {
				log.info("User tried to send message to unknown recipient [{}]", request.recipient());
				throw new ForbiddenException("Unknown recipient");
			}
		} catch (RepositoryException e) {
			log.warn("Recipient lookup failed for [{}]: {}", request.recipient(), e.getMessage());
			throw new ForbiddenException("Unknown recipient");
		}

		String replyTo = null;
		try {
			String principalName = principalManager.getPrincipalName(principalManager.getAuthenticatedUserURI());
			if (principalName != null && principalName.contains("@")) {
				replyTo = principalName;
			}
		} catch (Exception e) {
			log.warn("Could not resolve principal name for Reply-To header: {}", e.getMessage());
		}

		if (request.transport() == TransportType.EMAIL) {
			boolean sent = Email.sendMessage(
					repositoryManager.getConfiguration(),
					request.recipient(), request.subject(), request.body(), null, replyTo);
			if (!sent) {
				log.error("Failed to send email to [{}] with subject [{}]", request.recipient(), request.subject());
				throw new InternalServerErrorException("Failed to send email message");
			}
		}
	}
}
