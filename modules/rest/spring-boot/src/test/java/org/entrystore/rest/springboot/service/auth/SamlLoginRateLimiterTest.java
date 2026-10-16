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

package org.entrystore.rest.springboot.service.auth;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlLoginRateLimiterTest {

	private static final String CLIENT = "192.0.2.1";

	private final AtomicLong nanos = new AtomicLong();
	// The application's conversion service, which SpringApplication installs, reads the "1m" default as a Duration
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> context.getBeanFactory()
					.setConversionService(ApplicationConversionService.getSharedInstance()))
			.withUserConfiguration(SamlLoginRateLimiter.class)
			.withBean(Ticker.class, () -> nanos::get);

	@Test
	void allowsSixtyLoginStartsPerClientAndMinuteByDefault() {
		contextRunner.withPropertyValues("entrystore.auth.saml.enabled=true").run(context -> {
			var rateLimiter = context.getBean(SamlLoginRateLimiter.class);

			for (int i = 1; i <= 60; i++) {
				assertTrue(rateLimiter.tryAcquirePermit(CLIENT), "login start " + i);
			}
			assertFalse(rateLimiter.tryAcquirePermit(CLIENT));

			nanos.addAndGet(Duration.ofMinutes(1).toNanos());
			assertTrue(rateLimiter.tryAcquirePermit(CLIENT));
		});
	}

	// Users behind one NAT address start logins all day; a counted start must not push the window back, or the
	// count would never reset and the address would be refused far below 60 per minute.
	@Test
	void steadyLoginStartsBelowTheLimitAreNeverRefused() {
		var rateLimiter = new SamlLoginRateLimiter(60, Duration.ofMinutes(1), nanos::get);

		for (int i = 1; i <= 120; i++) {
			nanos.addAndGet(Duration.ofSeconds(30).toNanos());
			assertTrue(rateLimiter.tryAcquirePermit(CLIENT), "login start " + i + " of one every 30 s");
		}
	}

	@Test
	void absentWhenSamlIsDisabled() {
		contextRunner.run(context ->
				assertTrue(context.getBeansOfType(SamlLoginRateLimiter.class).isEmpty()));
	}

	@Test
	void nonIpAddressesShareOneBudget() {
		var rateLimiter = new SamlLoginRateLimiter(1, Duration.ofMinutes(1), nanos::get);

		assertTrue(rateLimiter.tryAcquirePermit("unknown"));
		assertFalse(rateLimiter.tryAcquirePermit("_hidden"));
	}
}
