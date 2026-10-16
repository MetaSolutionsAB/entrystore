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
import org.entrystore.rest.springboot.configuration.ConditionalOnBooleanConfig;
import org.entrystore.rest.springboot.service.ClientAddressRateLimiter;
import org.entrystore.rest.springboot.util.OldestFirstBoundedCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Limits SAML login starts per client address, as {@link ClientAddressRateLimiter} derives it, so that one address
 * cannot fill {@code CacheSaml2AuthenticationRequestRepository}, which every login start writes, or
 * {@link SamlAuthStateCache}, which a start with a whitelisted successurl or failureurl writes. A client that can vary
 * the address it reports is bounded only by the caches' {@link OldestFirstBoundedCache}. Users behind one NAT address
 * share a budget.
 */
@Service
@ConditionalOnBooleanConfig("entrystore.auth.saml.enabled")
public class SamlLoginRateLimiter extends ClientAddressRateLimiter {

	public SamlLoginRateLimiter(
			@Value("${entrystore.auth.saml.rate.limit.max:60}") int max,
			@Value("${entrystore.auth.saml.rate.limit.window:1m}") Duration window,
			Ticker ticker) {
		super(max, window, "saml-login", ticker);
	}
}
