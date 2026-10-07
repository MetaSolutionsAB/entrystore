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

package org.entrystore.rest.springboot.filter;

import jakarta.servlet.http.MappingMatch;
import org.entrystore.rest.springboot.configuration.AuthFeatureProperties;
import org.entrystore.rest.springboot.configuration.CorsProperties;
import org.entrystore.rest.springboot.configuration.EntryStoreCorsConfigurationSource;
import org.entrystore.rest.springboot.configuration.PasswordLoginMode;
import org.entrystore.rest.springboot.util.ErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledRouteFilterTest {

	private static final List<String> CANDIDATES = List.of("/auth/signup", "/auth/signup/confirm", "/auth/signupX",
			"/auth/pwreset", "/auth/pwreset/confirm", "/auth/pwresets", "/auth/login", "/auth/login/x", "/auth/cookie",
			"/auth/cookieX", "/auth/user", "/auth/logout");

	private static final AuthFeatureProperties SIGNUP_OFF = new AuthFeatureProperties(false, true);

	private static final CorsProperties CORS_OFF = new CorsProperties(false, "*", "", "", -1);

	private static final CorsProperties CORS_ON =
			new CorsProperties(true, "http://example.com", "http://localhost:3000", "X-Custom-Header", 7200);

	@Test
	void signupOff_answersEverySignupRouteAndNoOther() throws Exception {
		assertEquals(List.of("/auth/signup", "/auth/signup/confirm"), answeredWith404(SIGNUP_OFF));
	}

	@Test
	void passwordResetOff_answersEveryPasswordResetRouteAndNoOther() throws Exception {
		assertEquals(List.of("/auth/pwreset", "/auth/pwreset/confirm"),
				answeredWith404(new AuthFeatureProperties(true, false)));
	}

	@Test
	void passwordLoginOff_answersBothLoginRoutesAndNoOther() throws Exception {
		assertEquals(List.of("/auth/login", "/auth/cookie"),
				answeredWith404(new AuthFeatureProperties(true, true), PasswordLoginMode.OFF));
	}

	@Test
	void passwordLoginWhitelist_answersNoRoute() throws Exception {
		assertEquals(List.of(), answeredWith404(new AuthFeatureProperties(true, true), PasswordLoginMode.WHITELIST));
	}

	@Test
	void everythingOn_answersNoRoute() throws Exception {
		assertEquals(List.of(), answeredWith404(new AuthFeatureProperties(true, true)));
	}

	@Test
	void disabledRoute_writesTheJsonNotFoundEnvelope() throws Exception {
		var request = new MockHttpServletRequest("POST", "/auth/signup");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter(SIGNUP_OFF, CORS_OFF).doFilter(request, response, chain);

		assertNull(chain.getRequest(), "a disabled route must not reach the security chain or the handler");
		assertEquals(404, response.getStatus());
		assertTrue(response.getContentType().startsWith("application/json"));
		var body = response.getContentAsString();
		assertTrue(body.contains("\"status\":404") && body.contains("\"error\":\"Not Found\"")
				&& body.contains("\"path\":\"/auth/signup\""), body);
	}

	@Test
	void matchesRelativeToTheContextPath() throws Exception {
		var request = new MockHttpServletRequest("POST", "/store/auth/signup/confirm");
		request.setContextPath("/store");

		assertEquals(404, filtered(request).getStatus());
	}

	@Test
	void matchesBelowAPathPrefixServletMapping() throws Exception {
		// spring.mvc.servlet.path=/api maps the DispatcherServlet to /api/*, below which handlers are looked up
		var request = new MockHttpServletRequest("POST", "/store/api/auth/signup");
		request.setContextPath("/store");
		request.setServletPath("/api");
		request.setPathInfo("/auth/signup");
		request.setHttpServletMapping(new MockHttpServletMapping("auth/signup", "/api/*", "dispatcherServlet",
				MappingMatch.PATH));

		assertEquals(404, filtered(request).getStatus());
	}

	@Test
	void matchesBelowAServletPathWithoutMappingInformation() throws Exception {
		// The AntPathMatcher strategy looks handlers up by UrlPathHelper's path within the servlet mapping
		var request = new MockHttpServletRequest("POST", "/store/api/auth/signup");
		request.setContextPath("/store");
		request.setServletPath("/api");
		request.setPathInfo("/auth/signup");

		assertEquals(404, filtered(request).getStatus());
	}

	@Test
	void matrixParametersDoNotEscapeTheMatch() throws Exception {
		// Spring MVC ignores ;params when it matches a handler, so the gate must as well
		assertEquals(404, filtered(new MockHttpServletRequest("POST", "/auth/signup;x=1")).getStatus());
	}

	@Test
	void percentEncodedSegmentDoesNotEscapeTheMatch() throws Exception {
		assertEquals(404, filtered(new MockHttpServletRequest("POST", "/auth/sign%75p")).getStatus());
	}

	@Test
	void trailingSlashDoesNotEscapeTheMatch() throws Exception {
		assertEquals(404, filtered(new MockHttpServletRequest("POST", "/auth/signup/")).getStatus());
	}

	@Test
	void passingRequest_isLeftWithoutAParsedRequestPath() throws Exception {
		// A cached path would be reused by the DispatcherServlet instead of the one it parses itself
		var request = new MockHttpServletRequest("GET", "/auth/user");
		var chain = new MockFilterChain();

		filter(SIGNUP_OFF, CORS_OFF).doFilter(request, new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
		assertFalse(ServletRequestPathUtils.hasParsedRequestPath(request));
	}

	@Test
	void corsOn_crossOriginRequest_getsTheCorsHeadersOnThe404() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/signup");
		request.addHeader("Origin", "http://localhost:3000");
		var response = new MockHttpServletResponse();

		filter(SIGNUP_OFF, CORS_ON).doFilter(request, response, new MockFilterChain());

		assertEquals(404, response.getStatus());
		assertEquals("http://localhost:3000", response.getHeader("Access-Control-Allow-Origin"));
		assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
	}

	@Test
	void corsOn_preflight_isAnsweredByTheCorsPolicyWithoutA404() throws Exception {
		var request = new MockHttpServletRequest("OPTIONS", "/auth/signup");
		request.addHeader("Origin", "http://example.com");
		request.addHeader("Access-Control-Request-Method", "POST");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter(SIGNUP_OFF, CORS_ON).doFilter(request, response, chain);

		assertNull(chain.getRequest());
		assertEquals(200, response.getStatus());
		assertEquals("http://example.com", response.getHeader("Access-Control-Allow-Origin"));
		assertEquals("", response.getContentAsString());
	}

	@Test
	void corsOn_requestFromAnOriginOutsideThePolicy_gets404WithoutCorsHeaders() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/signup");
		request.addHeader("Origin", "http://example.com.evil");
		var response = new MockHttpServletResponse();

		filter(SIGNUP_OFF, new CorsProperties(true, "http://example.com", "", "", -1))
				.doFilter(request, response, new MockFilterChain());

		assertEquals(404, response.getStatus());
		assertNull(response.getHeader("Access-Control-Allow-Origin"));
	}

	@Test
	void corsOff_crossOriginRequest_getsNoCorsHeaders() throws Exception {
		var request = new MockHttpServletRequest("GET", "/auth/signup");
		request.addHeader("Origin", "http://example.com");
		var response = new MockHttpServletResponse();

		filter(SIGNUP_OFF, CORS_OFF).doFilter(request, response, new MockFilterChain());

		assertEquals(404, response.getStatus());
		assertNull(response.getHeader("Access-Control-Allow-Origin"));
		assertNull(response.getHeader("Vary"));
	}

	private static MockHttpServletResponse filtered(MockHttpServletRequest request) throws Exception {
		var response = new MockHttpServletResponse();
		filter(SIGNUP_OFF, CORS_OFF).doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static List<String> answeredWith404(AuthFeatureProperties authFeatures) throws Exception {
		return answeredWith404(authFeatures, PasswordLoginMode.ON);
	}

	private static List<String> answeredWith404(AuthFeatureProperties authFeatures, PasswordLoginMode passwordLoginMode)
			throws Exception {
		var filter = new DisabledRouteFilter(authFeatures, passwordLoginMode,
				new ErrorResponseWriter(JsonMapper.builder().build()), CORS_OFF,
				new EntryStoreCorsConfigurationSource(CORS_OFF));
		var answered = Stream.<String>builder();
		for (String path : CANDIDATES) {
			var response = new MockHttpServletResponse();
			var chain = new MockFilterChain();
			filter.doFilter(new MockHttpServletRequest("POST", path), response, chain);
			if (response.getStatus() == 404) {
				assertNull(chain.getRequest());
				answered.add(path);
			} else {
				assertNotNull(chain.getRequest(), path + " must pass through");
			}
		}
		return answered.build().toList();
	}

	private static DisabledRouteFilter filter(AuthFeatureProperties authFeatures, CorsProperties cors) {
		return new DisabledRouteFilter(authFeatures, PasswordLoginMode.ON,
				new ErrorResponseWriter(JsonMapper.builder().build()), cors,
				new EntryStoreCorsConfigurationSource(cors));
	}
}
