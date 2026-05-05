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
import org.entrystore.rest.springboot.service.FixedWindowRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class PasswordResetRateLimiter extends FixedWindowRateLimiter {

	public PasswordResetRateLimiter(
			@Value("${entrystore.auth.password-reset.rate.limit.max:10}") int max,
			@Value("${entrystore.auth.password-reset.rate.limit.window:1h}") Duration window,
			Ticker ticker) {
		super(max, window, "password-reset", ticker);
	}
}
