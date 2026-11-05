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

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Replaces the {@link ForwardedHeaderFilter} that {@code server.forward-headers-strategy: framework} registers, which
 * reads the RFC 7239 {@code Forwarded} header before {@code X-Forwarded-*}. Reverse proxies typically overwrite
 * {@code X-Forwarded-For} but pass a client's {@code Forwarded} through, which would let the client choose the
 * address that keys the per-IP rate limits. This filter reads only {@code X-Forwarded-*} and still hides
 * {@code Forwarded} from the application. Boot registers its own filter only when no such bean exists.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeaderConfiguration {

	/** Same dispatcher types and order as the filter Boot would register. */
	@Bean
	@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
	FilterRegistrationBean<ForwardedHeaderFilter> xForwardedHeaderFilter() {
		ForwardedHeaderFilter filter = new ForwardedHeaderFilter(false);
		filter.setUseForwardedPrefix(true);
		FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
