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

package org.entrystore.rest.springboot.model.exception;

/**
 * The REST layer's single exception for an explicit policy denial, unifying the behaviour in
 * {@code AppExceptionHandler}.
 *
 * <p><b>The status depends on the caller, not on this type.</b> An anonymous caller gets
 * <b>401 Unauthorized</b> with only the reason phrase as the {@code error} field; an authenticated
 * caller gets <b>403 Forbidden</b> with this exception's message. Both are logged at {@code info}.
 * Write the message to be user-facing: on the 403 path it is returned to the client verbatim.
 *
 * <p>There is deliberately no sibling {@code UnauthorizedException}. One existed until
 * ENTRYSTORE-1055 and was handled by this same handler with an identical mapping, so the choice
 * between the two carried no information and merely invited call sites to imply a status they did
 * not control.
 *
 * <p><b>Do not use this for a denial that reveals whether a particular entry exists.</b> Core's
 * {@code AuthorizationException} is answered <b>404</b> for anonymous callers rather than 401, to stop a
 * guest distinguishing "this entry exists but is private" from "no such entry" (CWE-204). That masking
 * is a property of <em>the check</em>, not of the layer it lives in: a per-entity permission check
 * written here — {@code if (!mayRead) throw new ForbiddenException(...)} on
 * {@code GET /{context-id}/entry/{entry-id}} — would answer 401 for a private entry while
 * {@code EntityNotFoundException} answers 404 for a missing one, reopening exactly that oracle. Let the
 * core ACL check raise {@code AuthorizationException} instead. Use this exception for denials whose
 * outcome does not depend on a specific entry existing.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}
}
