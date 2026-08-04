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

import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.springboot.model.api.SendMessageRequestBody;
import org.entrystore.rest.springboot.model.api.TransportType;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.ForbiddenException;
import org.entrystore.rest.springboot.model.exception.UnauthorizedException;
import org.entrystore.rest.springboot.util.CapturingAppender;
import org.entrystore.rest.springboot.util.EmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceTest {

	private static final String RECIPIENT = "recipient@example.com";
	private static final URI SENDER_URI = URI.create("http://example.com/_principals/resource/3");

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private MessageRateLimiter messageRateLimiter;

	@Mock
	private EmailSender emailSender;

	private MessageService service;

	private CapturingAppender logAppender;

	@BeforeEach
	void setUp() {
		service = new MessageService(principalManager, messageRateLimiter, emailSender);

		when(principalManager.currentUserIsGuest()).thenReturn(false);
		when(principalManager.getAuthenticatedUserURI()).thenReturn(SENDER_URI);
		when(principalManager.getPrincipalEntry(RECIPIENT)).thenReturn(mock(Entry.class));
		when(principalManager.getPrincipalName(SENDER_URI)).thenReturn("sender@example.com");

		logAppender = CapturingAppender.attachTo(MessageService.class);
	}

	@AfterEach
	void releaseLogging() {
		logAppender.close();
	}

	@Test
	void sendMessage_deliversWithTheSendersPrincipalNameAsReplyTo() {
		when(emailSender.sendMessage(eq(RECIPIENT), anyString(), anyString(), isNull(), anyString()))
				.thenReturn(true);

		service.sendMessage(request("Hello", "<p>Body</p>"));

		// From stays null so EmailSender uses the deployment's configured envelope address; Reply-To is the
		// sender's own principal name, which is why it must be exactly one address downstream.
		verify(emailSender).sendMessage(RECIPIENT, "Hello", "<p>Body</p>", null, "sender@example.com");
		verify(messageRateLimiter).acquirePermit(SENDER_URI.toString());
	}

	@Test
	void sendMessage_failedSend_becomesA503() {
		// This branch had no coverage at any level, so nothing pinned that a send failure surfaces as
		// Service Unavailable rather than a 500 or a silent success.
		when(emailSender.sendMessage(anyString(), anyString(), anyString(), any(), any())).thenReturn(false);

		CustomResponseException e = assertThrows(CustomResponseException.class,
				() -> service.sendMessage(request("Hello", "<p>Body</p>")));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
	}

	@Test
	void sendMessage_failedSend_doesNotLogARawCrLfFromTheSubject() {
		// HtmlSanitizer.sanitizeToPlainText strips markup and then HTML-unescapes, so &#13;&#10; in a
		// caller-supplied subject arrives here as a real CRLF and would forge lines in the ERROR log an
		// operator uses for abuse triage.
		when(emailSender.sendMessage(anyString(), anyString(), anyString(), any(), any())).thenReturn(false);

		assertThrows(CustomResponseException.class, () -> service.sendMessage(
				request("Hi&#13;&#10;ERROR Fake log line", "<p>Body</p>")));

		assertTrue(logAppender.allMessages()
						.noneMatch(message -> message.contains("\r") || message.contains("\n")),
				"got: " + logAppender);
	}

	@Test
	void sendMessage_guestUser_isRejectedWithoutSending() {
		when(principalManager.currentUserIsGuest()).thenReturn(true);

		assertThrows(UnauthorizedException.class, () -> service.sendMessage(request("Hello", "<p>Body</p>")));
		verifyNoInteractions(emailSender);
	}

	@Test
	void sendMessage_unknownRecipient_isRejectedWithoutSending() {
		when(principalManager.getPrincipalEntry(RECIPIENT)).thenReturn(null);

		assertThrows(ForbiddenException.class, () -> service.sendMessage(request("Hello", "<p>Body</p>")));
		verifyNoInteractions(emailSender);
	}

	@Test
	void sendMessage_subjectThatSanitizesToNothing_isRejectedWithoutSending() {
		assertThrows(BadRequestException.class,
				() -> service.sendMessage(request("<script>alert(1)</script>", "<p>Body</p>")));
		verifyNoInteractions(emailSender);
	}

	private static SendMessageRequestBody request(String subject, String body) {
		return new SendMessageRequestBody(TransportType.EMAIL, subject, RECIPIENT, body);
	}
}
