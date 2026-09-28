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

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Opt-in per-IP rate limiter for the guest-accessible {@code /search} endpoint, keyed by the resolved
 * client IP (honouring {@code entrystore.trust.x-forwarded-for} when proxied). Disabled by default:
 * it applies to every caller, so pipelines, the EntryScape API and users behind one NAT address share
 * a single budget and are blocked. Enable it with {@code entrystore.solr.search.rate.limit.max} only
 * where no high-volume clients share an IP.
 */
@Service
public class SearchRateLimiter extends FixedWindowRateLimiter {

	public SearchRateLimiter(
			@Value("${entrystore.solr.search.rate.limit.max:0}") int max,
			@Value("${entrystore.solr.search.rate.limit.window:1m}") Duration window,
			Ticker ticker) {
		super(max, window, "search", ticker, false);
	}
}
