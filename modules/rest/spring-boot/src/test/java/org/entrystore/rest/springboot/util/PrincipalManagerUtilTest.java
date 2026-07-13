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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrincipalManagerUtilTest {

	private static final URI PREVIOUS = URI.create("http://localhost/store/_principals/resource/42");
	private static final URI ADMIN = URI.create("http://localhost/store/_principals/resource/_admin");

	@Mock
	private PrincipalManager pm;

	@Mock
	private User adminUser;

	@Test
	void restoreAuthenticatedUserSafely_happyPath_withNullPrimary_returnsSilently() {

		assertDoesNotThrow(() -> PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS, null));

		verify(pm).setAuthenticatedUserURI(PREVIOUS);
	}

	@Test
	void restoreAuthenticatedUserSafely_happyPath_withPrimary_leavesPrimaryUntouched() {
		// A successful cleanup must NOT decorate the primary with a suppressed entry — that would lie
		// about a failure that did not occur.
		RuntimeException tryBodyFailure = new RuntimeException("try body blew up");

		PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS, tryBodyFailure);

		assertArrayEquals(new Throwable[0], tryBodyFailure.getSuppressed(),
				"successful cleanup should not attach any suppressed exception to the primary");
	}

	@Test
	void restoreAuthenticatedUserSafely_cleanupThrows_withNullPrimary_propagatesCleanupException() {
		// With no primary to attach to, the caller's finally block must surface the cleanup failure
		// itself — that is the only signal the request failed at all.
		RuntimeException cleanupFailure = new RuntimeException("restore failed");
		doThrow(cleanupFailure).when(pm).setAuthenticatedUserURI(PREVIOUS);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS, null));

		assertSame(cleanupFailure, thrown, "the original cleanup exception should propagate unchanged");
	}

	@Test
	void restoreAuthenticatedUserSafely_cleanupThrows_withPrimary_attachesAsSuppressedAndDoesNotThrow() {
		// This is the anti-masking branch the ticket targets: the try body's primary exception must
		// keep propagating; the cleanup failure rides along as a suppressed exception so neither is
		// lost.
		RuntimeException tryBodyFailure = new RuntimeException("try body blew up");
		RuntimeException cleanupFailure = new RuntimeException("restore failed");
		doThrow(cleanupFailure).when(pm).setAuthenticatedUserURI(PREVIOUS);

		assertDoesNotThrow(() -> PrincipalManagerUtil.restoreAuthenticatedUserSafely(pm, PREVIOUS, tryBodyFailure),
				"helper must not throw when primary is present — the caller already has an in-flight exception to surface");

		Throwable[] suppressed = tryBodyFailure.getSuppressed();
		assertArrayEquals(new Throwable[]{cleanupFailure}, suppressed,
				"cleanup failure should be attached to primary as the sole suppressed exception");
	}

	@Test
	void runAsAdmin_returnsSupplierValueAndRestoresPreviousUser() {
		stubAdminElevation();
		List<URI> uriChanges = recordUriChanges();

		String result = PrincipalManagerUtil.runAsAdmin(pm, () -> "value");

		assertEquals("value", result);
		assertEquals(List.of(ADMIN, PREVIOUS), uriChanges, "must elevate to admin, then restore the previous user");
	}

	@Test
	void runAsAdmin_runsActionWhileImpersonatingAdmin() {
		stubAdminElevation();
		List<URI> uriChanges = recordUriChanges();

		PrincipalManagerUtil.runAsAdmin(pm, () -> assertEquals(List.of(ADMIN), uriChanges, "action must run after elevation and before restore"));

		assertEquals(List.of(ADMIN, PREVIOUS), uriChanges);
	}

	@Test
	void runAsAdmin_actionThrows_restoresAndRethrows() {
		stubAdminElevation();
		RuntimeException actionFailure = new RuntimeException("action blew up");
		Runnable failingAction = () -> {
			throw actionFailure;
		};

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> PrincipalManagerUtil.runAsAdmin(pm, failingAction));

		assertSame(actionFailure, thrown);
		verify(pm).setAuthenticatedUserURI(PREVIOUS);
	}

	@Test
	void runAsAdmin_restoreFailsAfterActionThrew_attachesCleanupFailureAsSuppressed() {
		stubAdminElevation();
		RuntimeException actionFailure = new RuntimeException("action blew up");
		RuntimeException cleanupFailure = new RuntimeException("restore failed");
		// stub both argument values: strict stubbing rejects the elevation call (ADMIN) when only
		// the restore call (PREVIOUS) has a stubbing on the same method
		doNothing().when(pm).setAuthenticatedUserURI(ADMIN);
		doThrow(cleanupFailure).when(pm).setAuthenticatedUserURI(PREVIOUS);
		Runnable failingAction = () -> {
			throw actionFailure;
		};

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> PrincipalManagerUtil.runAsAdmin(pm, failingAction));

		assertSame(actionFailure, thrown, "the action's exception must keep propagating");
		assertArrayEquals(new Throwable[]{cleanupFailure}, thrown.getSuppressed());
	}

	@Test
	void runAsAdmin_restoreFailsAfterActionSucceeded_propagatesCleanupFailure() {
		stubAdminElevation();
		RuntimeException cleanupFailure = new RuntimeException("restore failed");
		// stub both argument values: strict stubbing rejects the elevation call (ADMIN) when only
		// the restore call (PREVIOUS) has a stubbing on the same method
		doNothing().when(pm).setAuthenticatedUserURI(ADMIN);
		doThrow(cleanupFailure).when(pm).setAuthenticatedUserURI(PREVIOUS);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> PrincipalManagerUtil.runAsAdmin(pm, () -> "value"));

		assertSame(cleanupFailure, thrown,
				"with no action exception to attach to, a failed restore must propagate — not be swallowed");
	}

	@Test
	void runAsAdmin_functionOverload_passesPreElevationUserToAction() {
		stubAdminElevation();
		List<URI> uriChanges = recordUriChanges();

		URI observed = PrincipalManagerUtil.runAsAdmin(pm, previous -> previous);

		assertEquals(PREVIOUS, observed, "action must receive the pre-elevation user, not the admin user");
		assertEquals(List.of(ADMIN, PREVIOUS), uriChanges, "must elevate to admin, then restore the previous user");
	}

	private void stubAdminElevation() {
		when(pm.getAuthenticatedUserURI()).thenReturn(PREVIOUS);
		when(pm.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(ADMIN);
	}

	private List<URI> recordUriChanges() {
		List<URI> uriChanges = new ArrayList<>();
		doAnswer(invocation -> uriChanges.add(invocation.getArgument(0)))
				.when(pm).setAuthenticatedUserURI(any());
		return uriChanges;
	}
}
