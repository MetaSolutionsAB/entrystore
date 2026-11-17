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

package org.entrystore.rest.springboot.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.entrystore.rest.springboot.util.SessionIdExceptions;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.util.List;

/**
 * Keeps flash maps in the session, as Spring MVC's default does, and treats a session that a concurrent request has
 * just ended as one without flash maps, as for a request without a session. Spring MVC reads them on every request.
 * Any exception that {@link SessionIdExceptions} does not recognise propagates.
 */
class EndedSessionTolerantFlashMapManager extends SessionFlashMapManager {

	@Override
	protected @Nullable List<FlashMap> retrieveFlashMaps(HttpServletRequest request) {
		try {
			return super.retrieveFlashMaps(request);
		} catch (IllegalStateException e) {
			rethrowUnlessSessionEnded(e);
			return null;
		}
	}

	@Override
	protected void updateFlashMaps(List<FlashMap> flashMaps, HttpServletRequest request,
								   HttpServletResponse response) {
		try {
			super.updateFlashMaps(flashMaps, request, response);
		} catch (IllegalStateException e) {
			rethrowUnlessSessionEnded(e);
		}
	}

	@Override
	protected @Nullable Object getFlashMapsMutex(HttpServletRequest request) {
		try {
			return super.getFlashMapsMutex(request);
		} catch (IllegalStateException e) {
			rethrowUnlessSessionEnded(e);
			return null;
		}
	}

	private static void rethrowUnlessSessionEnded(IllegalStateException e) {
		if (!SessionIdExceptions.foundIn(e)) {
			throw e;
		}
	}
}
