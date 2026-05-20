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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.entrystore.rest.springboot.model.auth.SignupInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private ExecutorService executor;

	private MeterRegistry meterRegistry;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		// AuthService has 11 collaborators via @RequiredArgsConstructor; only meterRegistry is
		// touched by submitPasswordResetDispatch so the rest stay null. The real executor created
		// by init() is replaced below with a Mockito mock so we can program the rejection path.
		meterRegistry = new SimpleMeterRegistry();
		authService = new AuthService(null, null, null, null, null, null, null, null, null, null, meterRegistry);
		// Register the Micrometer counter without standing up the real ThreadPoolExecutor — we'll
		// inject a mocked ExecutorService explicitly per test.
		ReflectionTestUtils.setField(authService, "passwordResetRejectedCounter",
				meterRegistry.counter("auth.pwreset.rejected"));
		ReflectionTestUtils.setField(authService, "passwordResetExecutor", executor);
	}

	@Test
	void submitPasswordResetDispatch_rejectedExecution_incrementsCounterAndSwallows() {
		// Pins the security contract of finding #6: a saturated queue (or shutting-down executor)
		// must (a) not propagate to the caller — otherwise the active-user branch surfaces 500 while
		// nonexistent/disabled stay 200, re-opening the enumeration oracle, and (b) increment the
		// Micrometer counter so operators can alert on sustained drops via monitoring instead of
		// log scraping.
		doThrow(new RejectedExecutionException("queue saturated")).when(executor).execute(any());
		SignupInfo ci = new SignupInfo();
		ci.setEmail("alice@example.com");

		assertDoesNotThrow(() -> authService.submitPasswordResetDispatch(ci, "anyPassword"));

		assertEquals(1.0, meterRegistry.counter("auth.pwreset.rejected").count(),
				"RejectedExecutionException must increment the auth.pwreset.rejected counter");
	}

	@Test
	void submitPasswordResetDispatch_happyPath_doesNotIncrementCounter() {
		// Negative: a normal execute() must not increment the rejection counter. A regression
		// that always increments would fire spurious alerts on every reset. Default Mockito stub
		// for execute() is a no-op, so the runnable is never run.
		SignupInfo ci = new SignupInfo();
		ci.setEmail("alice@example.com");

		assertDoesNotThrow(() -> authService.submitPasswordResetDispatch(ci, "anyPassword"));

		assertEquals(0.0, meterRegistry.counter("auth.pwreset.rejected").count(),
				"Happy path must not increment the rejection counter");
	}
}
