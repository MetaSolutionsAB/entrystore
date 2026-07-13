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
import org.entrystore.PrincipalManager;

import java.net.URI;
import java.util.function.Supplier;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PrincipalManagerUtil {

	/**
	 * Runs {@code action} with the per-thread authenticated user elevated to the admin user, restoring
	 * the previous user in all cases (including when {@code action} throws) via
	 * {@link #restoreAuthenticatedUserSafely(PrincipalManager, URI, Throwable)}.
	 * <p>
	 * If the action's body needs the pre-elevation user URI as a value (e.g. to set a creator or grant
	 * ACLs), the caller must capture {@code pm.getAuthenticatedUserURI()} before invoking this method.
	 */
	public static <T> T runAsAdmin(PrincipalManager pm, Supplier<T> action) {
		URI previous = pm.getAuthenticatedUserURI();
		Throwable primary = null;
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			return action.get();
		} catch (Throwable t) {
			primary = t;
			throw t;
		} finally {
			restoreAuthenticatedUserSafely(pm, previous, primary);
		}
	}

	/**
	 * Runs {@code action} as the admin user; see {@link #runAsAdmin(PrincipalManager, Supplier)}.
	 */
	public static void runAsAdmin(PrincipalManager pm, Runnable action) {
		runAsAdmin(pm, () -> {
			action.run();
			return null;
		});
	}

	/**
	 * Restore the per-thread authenticated user URI to {@code previous} from inside a {@code finally}
	 * block, without masking an exception thrown by the surrounding {@code try} body.
	 * <p>
	 * Intended usage — the caller captures any try-body exception via precise rethrow and passes it
	 * as {@code primary}:
	 * <pre>{@code
	 * URI previous = pm.getAuthenticatedUserURI();
	 * Throwable primary = null;
	 * try {
	 *     // body
	 * } catch (Throwable t) {
	 *     primary = t;
	 *     throw t;
	 * } finally {
	 *     PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, previous, primary);
	 * }
	 * }</pre>
	 * <p>
	 * If the restore call throws:
	 * <ul>
	 *   <li>when {@code primary != null}, the cleanup failure is attached to {@code primary} via
	 *       {@link Throwable#addSuppressed(Throwable)} so the original try-body exception still
	 *       propagates and the cleanup failure is preserved in the same error event;</li>
	 *   <li>when {@code primary == null}, the cleanup failure propagates unchanged.</li>
	 * </ul>
	 */
	public static void restoreAuthenticatedUserSafely(PrincipalManager pm, URI previous, Throwable primary) {
		try {
			pm.setAuthenticatedUserURI(previous);
		} catch (Throwable cleanupEx) {
			if (primary != null) {
				primary.addSuppressed(cleanupEx);
			} else {
				throw cleanupEx;
			}
		}
	}
}
