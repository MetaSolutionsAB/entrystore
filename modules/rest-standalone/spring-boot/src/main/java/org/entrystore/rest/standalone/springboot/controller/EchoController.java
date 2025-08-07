package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.standalone.springboot.model.exception.TextareaHtmlResponseException;
import org.entrystore.rest.standalone.springboot.service.EchoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;


@Controller
@RequiredArgsConstructor
public class EchoController {

	private final PrincipalManager principalManager;
	private final EchoService echoService;

	@Operation(summary = "Responds with an echo of the request body, back as escaped content inside an HTML textarea.")
	@PostMapping(
			path = "/echo",
			produces = MediaType.TEXT_HTML_VALUE)
	public String replyBackRequestContent(
			@RequestPart(value = "file", required = false) MultipartFile file,
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
		if (file == null) {
			throw new TextareaHtmlResponseException("Missing required 'file' in the request part", HttpStatus.BAD_REQUEST);
		}
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
