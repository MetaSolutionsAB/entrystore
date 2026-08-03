package org.entrystore.rest.springboot.service;

import lombok.RequiredArgsConstructor;
import org.entrystore.rest.springboot.configuration.EchoProperties;
import org.entrystore.rest.springboot.model.exception.TextareaHtmlResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class EchoService {

	private final EchoProperties echoProperties;

	public String readFileContentsAsString(MultipartFile file) {

		// We don't echo payloads bigger than the configured cap
		long maxFileSize = echoProperties.maxFileSize().toBytes();
		if (file.getSize() > maxFileSize) {
			throw new TextareaHtmlResponseException("Received file size (of " + file.getSize()
					+ "B) exceeds maximum allowed size of: " + maxFileSize + "B", HttpStatus.PAYLOAD_TOO_LARGE);
		}

		try {
			return file.getResource().getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new TextareaHtmlResponseException("Unable to read file content", HttpStatus.BAD_REQUEST, e);
		}
	}
}
