package org.entrystore.rest.standalone.springboot.service;

import org.entrystore.rest.standalone.springboot.model.exception.TextareaHtmlResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class EchoService {

	private final static long MAX_FILE_SIZE = 10 * 1024 * 1024;        // 10MB in bytes


	public String readFileContentsAsString(MultipartFile file) {

		// We don't echo payloads bigger than MAX_FILE_SIZE
		if (file.getSize() > MAX_FILE_SIZE) {
			throw new TextareaHtmlResponseException("Received file size (of " + file.getSize()
					+ "b) exceeds maximum allowed size of: " + MAX_FILE_SIZE + "b", HttpStatus.BAD_REQUEST);
		}

		try {
			return file.getResource().getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new TextareaHtmlResponseException("Unable to read file content. Error: " + e.getMessage(),
					HttpStatus.BAD_REQUEST);
		}
	}
}
