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

import org.entrystore.rest.springboot.configuration.MaxResourceSize;
import org.entrystore.rest.springboot.controller.AppExceptionHandler;
import org.entrystore.rest.springboot.util.WebResourceUrls;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limiter as Spring MVC runs it: a body over the limit is answered 413 by {@code AppExceptionHandler}, whichever
 * converter reads it, rather than turned into the 400 of a failed read.
 */
class RequestBodySizeLimiterWiringTest {

	private final RequestBodySizeLimiter limiter = new RequestBodySizeLimiter(new MaxResourceSize("1k"));

	@Test
	void contentLengthAboveTheLimit_isAnswered413() throws Exception {
		mvc().perform(post("/string").contentType(MediaType.TEXT_PLAIN).content("a".repeat(1025)))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.error")
						.value("Request body size (of 1025b) exceeds maximum allowed size of: 1024b"));
	}

	@Test
	void stringBodyReadPastTheLimit_isAnswered413() throws Exception {
		mvcWithoutContentLengthCheck()
				.perform(post("/string").contentType(MediaType.TEXT_PLAIN).content("a".repeat(1025)))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.error").value("Request body exceeds maximum allowed size of: 1024b"));
	}

	@Test
	void jsonBodyReadPastTheLimit_isAnswered413() throws Exception {
		String json = "{\"name\":\"" + "a".repeat(1024) + "\"}";

		mvcWithoutContentLengthCheck().perform(post("/json").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.error").value("Request body exceeds maximum allowed size of: 1024b"));
	}

	@Test
	void stringBodyAtTheLimit_reachesTheHandler() throws Exception {
		mvc().perform(post("/string").contentType(MediaType.TEXT_PLAIN).content("a".repeat(1024)))
				.andExpect(status().isOk())
				.andExpect(content().string("1024"));
	}

	@Test
	void jsonBodyAtTheLimit_reachesTheHandler() throws Exception {
		// 11 bytes of JSON around the name.
		String json = "{\"name\":\"" + "a".repeat(1013) + "\"}";

		mvc().perform(post("/json").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isOk())
				.andExpect(content().string("1013"));
	}

	private MockMvc mvc() {
		return builder().addInterceptors(limiter).build();
	}

	/**
	 * Without the interceptor: a {@code MockHttpServletRequest} always declares the length of its content, so this
	 * is how a body without Content-Length, or with one that understates it, reaches the read.
	 */
	private MockMvc mvcWithoutContentLengthCheck() {
		return builder().build();
	}

	private StandaloneMockMvcBuilder builder() {
		return MockMvcBuilders.standaloneSetup(new Handlers()).setControllerAdvice(limiter,
				new AppExceptionHandler(new WebResourceUrls("http://localhost:8181/store/")));
	}

	@RestController
	public static class Handlers {

		@PostMapping("/string")
		public String string(@RequestBody String body) {
			return String.valueOf(body.length());
		}

		@PostMapping("/json")
		public String json(@RequestBody Named body) {
			return String.valueOf(body.name().length());
		}
	}

	public record Named(String name) {
	}
}
