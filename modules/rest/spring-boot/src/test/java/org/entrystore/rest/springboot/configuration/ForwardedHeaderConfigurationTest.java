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

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ForwardedHeaderConfigurationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JettyServletWebServerAutoConfiguration.class));

	@Test
	void xForwardedForWinsOverForwardedFor() throws Exception {
		MockHttpServletRequest request = searchRequest();
		request.addHeader("Forwarded", "for=198.51.100.7");
		request.addHeader("X-Forwarded-For", "203.0.113.7");

		assertEquals("203.0.113.7", filtered(request).getRemoteAddr());
	}

	@Test
	void forwardedAloneChangesNothing() throws Exception {
		MockHttpServletRequest request = searchRequest();
		request.addHeader("Forwarded", "for=198.51.100.7;proto=https;host=evil.example");

		HttpServletRequest filtered = filtered(request);

		assertEquals("127.0.0.1", filtered.getRemoteAddr());
		assertEquals("http", filtered.getScheme());
		assertEquals("localhost", filtered.getServerName());
	}

	@Test
	void forwardedIsHiddenFromTheApplication() throws Exception {
		MockHttpServletRequest request = searchRequest();
		request.addHeader("Forwarded", "for=198.51.100.7");

		assertNull(filtered(request).getHeader("Forwarded"));
	}

	@Test
	void xForwardedPrefixStillSetsTheContextPath() throws Exception {
		MockHttpServletRequest request = searchRequest();
		request.addHeader("X-Forwarded-Prefix", "/public");

		HttpServletRequest filtered = filtered(request);

		assertEquals("/public", filtered.getContextPath());
		assertEquals("/public/search", filtered.getRequestURI());
	}

	@Test
	void replacesBootsFilterUnderTheFrameworkStrategy() {
		runner.withUserConfiguration(ForwardedHeaderConfiguration.class)
				.withPropertyValues("server.forward-headers-strategy=framework")
				.run(context -> assertEquals(Set.of("xForwardedHeaderFilter"), forwardedHeaderFilterBeans(context)));
	}

	/** Shows that the runner loads Boot's filter registration, so the test above can see it. */
	@Test
	void bootRegistersItsOwnFilterWithoutThisConfiguration() {
		runner.withPropertyValues("server.forward-headers-strategy=framework")
				.run(context -> assertEquals(Set.of("forwardedHeaderFilter"), forwardedHeaderFilterBeans(context)));
	}

	@Test
	void registersNoFilterWithoutTheFrameworkStrategy() {
		runner.withUserConfiguration(ForwardedHeaderConfiguration.class)
				.withPropertyValues("server.forward-headers-strategy=none")
				.run(context -> assertEquals(Set.of(), forwardedHeaderFilterBeans(context)));
	}

	private static MockHttpServletRequest searchRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/store/search");
		request.setContextPath("/store");
		return request;
	}

	/** The request the rest of the application sees after the filter. */
	private static HttpServletRequest filtered(MockHttpServletRequest request) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		new ForwardedHeaderConfiguration().xForwardedHeaderFilter().getFilter()
				.doFilter(request, new MockHttpServletResponse(), chain);
		return (HttpServletRequest) chain.getRequest();
	}

	private static Set<String> forwardedHeaderFilterBeans(ApplicationContext context) {
		return context.getBeansOfType(FilterRegistrationBean.class).entrySet().stream()
				.filter(entry -> entry.getValue().getFilter() instanceof ForwardedHeaderFilter)
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());
	}
}
