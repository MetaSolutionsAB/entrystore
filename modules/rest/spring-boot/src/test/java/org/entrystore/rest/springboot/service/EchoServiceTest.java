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

import org.entrystore.rest.springboot.configuration.EchoProperties;
import org.entrystore.rest.springboot.model.exception.TextareaHtmlResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EchoServiceTest {

	@Test
	void payloadWithinTheCap_isEchoedBack() {
		EchoService service = new EchoService(new EchoProperties(DataSize.ofBytes(16)));

		assertEquals("hello", service.readFileContentsAsString(fileOfSize("hello")));
	}

	@Test
	void payloadOverTheCap_is413AndTheMessageQuotesTheConfiguredLimit() {
		// The message is user-visible: AppExceptionHandler renders TextareaHtmlResponseException into the
		// textarea template, and EchoIT asserts the string byte-exactly. So the configured value has to
		// reach the message, not just the comparison.
		EchoService service = new EchoService(new EchoProperties(DataSize.ofBytes(4)));

		TextareaHtmlResponseException e = assertThrows(TextareaHtmlResponseException.class,
				() -> service.readFileContentsAsString(fileOfSize("hello")));

		assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatus());
		assertEquals("Received file size (of 5B) exceeds maximum allowed size of: 4B", e.getMessage());
	}

	@Test
	void payloadExactlyAtTheCap_isAccepted() {
		// The check is strictly greater-than, so the boundary value must pass.
		EchoService service = new EchoService(new EchoProperties(DataSize.ofBytes(5)));

		assertEquals("hello", service.readFileContentsAsString(fileOfSize("hello")));
	}

	@Test
	void nonPositiveCap_failsFastNamingTheKey() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new EchoProperties(DataSize.ofBytes(0)));

		assertEquals("entrystore.echo.max-file-size must be positive, got 0B", e.getMessage());
	}

	private static MockMultipartFile fileOfSize(String content) {
		return new MockMultipartFile("file", "echo.txt", "text/plain",
				content.getBytes(StandardCharsets.UTF_8));
	}
}
