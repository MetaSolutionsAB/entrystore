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

package org.entrystore.rest.springboot.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;

import java.net.URI;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PrincipalManagerUtil {

	/**
	 * Restore the per-thread authenticated user URI to {@code previous} from inside a {@code finally}
	 * block, without masking any exception thrown by the surrounding {@code try} body.
	 * <p>
	 * If the restore call throws, the failure is logged and the thread is forced to the guest URI as
	 * a defense-in-depth measure (so a Jetty worker thread cannot be returned to the pool with admin
	 * bound after a failed restore). If the clear-to-guest call also throws, this method throws
	 * {@link IllegalStateException} — the thread's auth state is irrecoverable and execution must not
	 * continue.
	 */
	public static void restoreAuthenticatedUserSafely(PrincipalManager pm, URI previous) {
		try {
			pm.setAuthenticatedUserURI(previous);
		} catch (Exception e) {
			log.error("Failed to restore authenticated user URI, clearing to guest", e);
			try {
				pm.setAuthenticatedUserURI(pm.getGuestUser().getURI());
			} catch (Exception clearEx) {
				log.error("Failed to clear authenticated user URI to guest", clearEx);
				throw new IllegalStateException(
						"Authenticated user URI is in an inconsistent state; aborting to prevent execution under unintended privileges",
						clearEx);
			}
		}
	}
}
