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

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrincipalManagerUtilTest {

	private static final URI PREVIOUS = URI.create("http://localhost/store/_principals/resource/42");
	private static final URI GUEST = URI.create("http://localhost/store/_principals/resource/_guest");

	@Mock
	private PrincipalManager pm;

	@Mock
	private User guestUser;

	@Test
	void restoreAuthenticatedUserSafely_happyPath_setsPreviousAndSkipsGuest() {
		doNothing().when(pm).setAuthenticatedUserURI(PREVIOUS);

		PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS);

		// Only the restore call should fire — guest fallback must NOT be touched on the happy path.
		verify(pm).setAuthenticatedUserURI(PREVIOUS);
		verify(pm, never()).getGuestUser();
	}

	@Test
	void restoreAuthenticatedUserSafely_restoreThrows_clearsToGuestAndSwallows() {
		when(pm.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(GUEST);
		doThrow(new RuntimeException("restore failed")).when(pm).setAuthenticatedUserURI(PREVIOUS);
		doNothing().when(pm).setAuthenticatedUserURI(GUEST);

		// No exception is propagated — the helper logs and continues.
		PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS);

		// The fallback path must (1) ask the PM for the guest user and (2) set the URI to guest, in order.
		InOrder order = inOrder(pm);
		order.verify(pm).setAuthenticatedUserURI(PREVIOUS);
		order.verify(pm).setAuthenticatedUserURI(GUEST);
	}

	@Test
	void restoreAuthenticatedUserSafely_bothCallsThrow_raisesIllegalStateWithClearAsCause() {
		RuntimeException restoreFailure = new RuntimeException("restore failed");
		RuntimeException clearFailure = new RuntimeException("clear failed");
		when(pm.getGuestUser()).thenReturn(guestUser);
		when(guestUser.getURI()).thenReturn(GUEST);
		doThrow(restoreFailure).when(pm).setAuthenticatedUserURI(PREVIOUS);
		doThrow(clearFailure).when(pm).setAuthenticatedUserURI(GUEST);

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS));

		// The clear failure (not the restore failure) is preserved as the cause, since at the moment we
		// give up the most recent failure is the most informative.
		assertSame(clearFailure, thrown.getCause(),
				"clear-to-guest failure should be the IllegalStateException's cause");
		assertEquals(
				"Authenticated user URI is in an inconsistent state; aborting to prevent execution under unintended privileges",
				thrown.getMessage());
	}
}
