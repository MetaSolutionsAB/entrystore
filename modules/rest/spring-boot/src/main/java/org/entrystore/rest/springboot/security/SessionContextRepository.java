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

package org.entrystore.rest.springboot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Keeps the SecurityContext in the HttpSession and remembers, per request, that it was loaded from there. Such a
 * request counts as containing its context for the rest of the request, also after a concurrent request (logout, a
 * revocation, the session lifetime) has invalidated the session. Otherwise SessionManagementFilter would take the
 * session's Authentication for a login made during this request and start and register a replacement session,
 * undoing the logout or revocation. The request itself finishes as the user, as any request in flight does, and the
 * session's next request is answered like any ended session.
 */
class SessionContextRepository extends HttpSessionSecurityContextRepository {

	private static final String LOADED_FROM_SESSION = SessionContextRepository.class.getName() + ".LOADED_FROM_SESSION";

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		DeferredSecurityContext fromSession = super.loadDeferredContext(request);
		return new DeferredSecurityContext() {
			@Override
			public SecurityContext get() {
				resolve();
				return fromSession.get();
			}

			@Override
			public boolean isGenerated() {
				return resolve();
			}

			private boolean resolve() {
				boolean generated;
				try {
					generated = fromSession.isGenerated();
				} catch (IllegalStateException endedBetweenLookupAndRead) {
					// the session is gone now, so this resolves to an empty context
					generated = fromSession.isGenerated();
				}
				if (!generated) {
					request.setAttribute(LOADED_FROM_SESSION, Boolean.TRUE);
				}
				return generated;
			}
		};
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		if (request.getAttribute(LOADED_FROM_SESSION) != null) {
			return true;
		}
		try {
			return super.containsContext(request);
		} catch (IllegalStateException endedBetweenLookupAndRead) {
			return false;
		}
	}
}
