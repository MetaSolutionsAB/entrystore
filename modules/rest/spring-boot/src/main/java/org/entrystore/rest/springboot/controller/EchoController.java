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

package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.springboot.model.exception.TextareaHtmlResponseException;
import org.entrystore.rest.springboot.service.EchoService;
import org.entrystore.rest.springboot.util.MultipartUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.util.WebUtils;

@Slf4j
@Controller
@RequiredArgsConstructor
public class EchoController {

	private final PrincipalManager principalManager;
	private final EchoService echoService;

	@Operation(
			summary = "Responds with an echo of the request body, back as escaped content inside an HTML textarea.",
			description = "The content should be sent as a file part of a multipart/form-data request. It may carry any " +
					"part name: a part named \"file\" is used when it carries a filename, otherwise the first part " +
					"carrying a filename, otherwise the first part carrying content.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			content = @Content(
					mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
					schema = @Schema(type = "object"),
					schemaProperties = @SchemaProperty(
							name = "file",
							schema = @Schema(type = "string", format = "binary"))))
	@PostMapping(
			path = "/echo",
			produces = MediaType.TEXT_HTML_VALUE)
	public String replyBackRequestContent(
			HttpServletRequest request,
			@RequestHeader("Content-Type") String contentType,
			Model model
	) {

		/* We could just add "consumes = MULTIPART_FORM_DATA_VALUE" or "@hasRole(USER|ADMIN)" to the endpoint declaration above,
		 so then it would accept only a valid multipart/form-data requests. However, then the default Spring-boot exceptions
		 would be thrown (a json response), but we need textarea response.
		 Hence, we accept any data, check if it's valid and throw Textarea HtmlResponse Exception in case it's not valid.

		 Accepting any data in this endpoint also helps to correctly route requests to the endpoint.
		 With "consumes = MULTIPART_FORM_DATA_VALUE" a request of "POST /echo" with json data would be routed to
		 "POST /{context-id}", as it matches both the path and accept-header
		 */

		if (contentType == null || !contentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)) {
			throw new TextareaHtmlResponseException("/echo endpoint accepts only 'multipart/form-data' requests", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		}

		// Resolved by hand instead of through a MultipartRequest method parameter: this endpoint has no
		// "consumes" on purpose (see above), so the argument resolver would fail on non-multipart requests
		// before the content-type check above could answer them with a textarea response.
		MultipartRequest multipartRequest = WebUtils.getNativeRequest(request, MultipartRequest.class);
		if (multipartRequest == null) {
			// The declared content type says multipart, so this is a server-side resolution problem
			// (no MultipartResolver, or spring.servlet.multipart.enabled=false), not a bad request.
			log.error("Request declares Content-Type '{}' but was not resolved as multipart; check the multipart configuration", contentType);
			throw new TextareaHtmlResponseException("Could not read the multipart request", HttpStatus.INTERNAL_SERVER_ERROR);
		}

		MultipartFile file = MultipartUtil.firstFilePart(multipartRequest)
				.orElseThrow(() -> new TextareaHtmlResponseException("Missing file part in the request", HttpStatus.BAD_REQUEST));

		// TODO this part should be taken out of here and implemented inside Spring Security
		if (principalManager.getGuestUser().getURI().equals(principalManager.getAuthenticatedUserURI())) {
			throw new TextareaHtmlResponseException("Guest account is not allowed to use /echo endpoint.", HttpStatus.FORBIDDEN);
		}

		String payload = echoService.readFileContentsAsString(file);
		String textAreaVal = "status:" + HttpStatus.OK.value() + "\n" + payload;

		// Thymeleaf will escape html characters when using "th:text" attribute
		model.addAttribute("textareaValue", textAreaVal);

		return "textarea";
	}
}
