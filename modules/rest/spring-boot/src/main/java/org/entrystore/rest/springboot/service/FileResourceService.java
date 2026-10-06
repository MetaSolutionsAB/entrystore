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

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.function.IOSupplier;
import org.apache.commons.io.input.ProxyInputStream;
import org.apache.commons.lang3.StringUtils;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.EntityNotFoundException;
import org.entrystore.rest.springboot.model.exception.EntityTooLargeException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.model.exception.RedirectSeeOtherException;
import org.entrystore.rest.springboot.util.FileUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Resource operations on binary (GraphType None) entries: storing uploaded data, locating the data file and its
 * media type for download, and deleting the data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileResourceService {

	private final RepositoryManagerImpl repositoryManager;

	@Value("${entrystore.http.allow-media-type-javascript:false}")
	@Setter(AccessLevel.PACKAGE)
	private boolean rewriteMediaTypeJavaScript;

	/**
	 * Streams the request body to the entry's data, enforcing the repository's maximum file size up front from
	 * {@code contentLength} (-1 when unknown) and while reading, and records its size, mimetype (the explicit
	 * {@code mimeType} parameter, else the request media type, else octet-stream) and, when one is supplied, the
	 * sanitized filename. The body may be of any size, so callers must check write access first; it is opened only
	 * once the up-front checks pass, as opening it makes Jetty answer Expect: 100-continue. Multipart content is
	 * rejected here; it goes through {@link #setDataMultipart}.
	 */
	public void setData(Entry entry, IOSupplier<InputStream> body, long contentLength, String mediaType,
						String mimeType, String filename) {
		if (MediaType.MULTIPART_FORM_DATA_VALUE.equals(mediaType)) {
			throw new BadRequestException("Content negotiation failure, Multipart file content should be handled by other endpoint.");
		}
		long maxFileSize = repositoryManager.getMaximumFileSize();
		rejectIfAboveMaximum(contentLength, maxFileSize);
		InputStream in;
		try {
			in = body.get();
		} catch (IOException e) {
			throw new BadRequestException("Failed to read the request body", e);
		}
		try {
			Data data = (Data) entry.getResource();
			data.setData(new RequestBodyInputStream(in, maxFileSize));
			entry.setFileSize(data.getDataFile().length());
			if (mimeType == null) {
				mimeType = Objects.requireNonNullElse(mediaType, MediaType.APPLICATION_OCTET_STREAM_VALUE);
			}
			entry.setMimetype(mimeType);
			if (StringUtils.isNotEmpty(filename)) {
				entry.setFilename(FileUtil.sanitizeFilename(filename));
			}
		} catch (QuotaException qe) {
			throw new EntityTooLargeException(qe.getMessage(), qe);
		} catch (SizeLimitExceededException e) {
			throw new EntityTooLargeException("Received file exceeds maximum allowed size of: " + maxFileSize + "b", e);
		} catch (RequestBodyReadException e) {
			throw new BadRequestException("Failed to read the request body", e);
		} catch (DataImpl.EntryRemovedException e) {
			throw new EntityNotFoundException("The entry was removed while its resource was uploaded", e);
		} catch (IOException ioe) {
			if (ioe.getCause() instanceof NullPointerException) {
				throw new BadRequestException("Invalid request data", ioe);
			} else {
				throw new InternalServerErrorException("Failed to process resource data", ioe);
			}
		}
		// TODO: when the `textarea` request parameter of PUT /{context-id}/resource/{entry-id} is set, errors
		// should be answered as TEXT_HTML (<textarea>{"error":"..."}</textarea>) by the exception handler.
	}

	/**
	 * Stores an uploaded file as the entry's data, enforcing the repository's maximum file size. Any graph type
	 * other than None is rejected as a bad request.
	 */
	public void setDataMultipart(Entry entry, MultipartFile file, String mimeType) {

		if (entry.getGraphType() != GraphType.None) {
			throw new BadRequestException("Cannot set resource for entry with GraphType " + entry.getGraphType() + ". Only None GraphType can set a multipart file.");
		}

		rejectIfAboveMaximum(file.getSize(), repositoryManager.getMaximumFileSize());
		try {
			((Data) entry.getResource()).setData(file.getInputStream());
			entry.setFileSize(((Data) entry.getResource()).getDataFile().length());
			String itemMimeType = file.getContentType();
			if (mimeType != null) {
				itemMimeType = mimeType;
			}
			entry.setMimetype(itemMimeType);
			String originalFilename = file.getOriginalFilename();
			if (StringUtils.isNotBlank(originalFilename)) {
				entry.setFilename(FileUtil.sanitizeFilename(originalFilename));
			} else {
				// The part name is client-controlled and carries no filename semantics, so it is not
				// used as a fallback. The entry id is, so that a name left over from an earlier
				// upload cannot end up describing the content stored now. It is sanitized like any
				// other stored filename: ids of imported entries do not go through the REST layer's
				// id validation.
				log.warn("Multipart upload for entry '{}' is missing the original filename, falling back to the entry id",
						entry.getEntryURI());
				entry.setFilename(FileUtil.sanitizeFilename(entry.getId()));
			}
		} catch (DataImpl.EntryRemovedException e) {
			throw new EntityNotFoundException("The entry was removed while its resource was uploaded", e);
		} catch (IOException ioe) {
			throw new InternalServerErrorException("Failed to process multipart resource data", ioe);
		} catch (QuotaException qe) {
			throw new EntityTooLargeException(qe.getMessage(), qe);
		}
	}

	private static void rejectIfAboveMaximum(long size, long maxFileSize) {
		if (maxFileSize != -1 && size > maxFileSize) {
			throw new EntityTooLargeException("Received file size (of " + size + "b) exceeds maximum allowed size of: "
					+ maxFileSize + "b");
		}
	}

	/**
	 * Returns the data file of an information resource, or null when none is available. A named resource has no
	 * data and redirects to its local metadata instead.
	 */
	public File dataFileForDownload(Entry entry) {

		if (entry.getResourceType() == ResourceType.InformationResource) {
			// Local data
			return ((Data) entry.getResource()).getDataFile();
		} else if (entry.getResourceType() == ResourceType.NamedResource) {
			// If there is no resource we redirect to the metadata
			throw new RedirectSeeOtherException(entry.getLocalMetadataURI());
		}
		// NOT USED YET
		//	if (ResourceType.Unknown.equals(entry.getResourceType())) {}
		return null;
	}

	/**
	 * Media type to serve the entry's data with: octet-stream when none is stored or the stored value does not
	 * parse, text/plain for JavaScript when {@code entrystore.http.allow-media-type-javascript} is {@code true}
	 * (the property name is historical: it enables the rewrite).
	 */
	public MediaType mediaTypeForDownload(Entry entry) {
		String medTyp = entry.getMimetype();
		if (medTyp == null) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		if (rewriteMediaTypeJavaScript && medTyp.toLowerCase().contains("javascript")) {
			log.info("Rewriting media type {} to text/plain for {}", medTyp, entry.getResourceURI());
			return MediaType.TEXT_PLAIN;
		}
		try {
			return MediaType.parseMediaType(medTyp);
		} catch (InvalidMediaTypeException e) {
			log.warn("Invalid stored media type [{}] for {}, serving as application/octet-stream", medTyp,
					entry.getEntryURI());
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	/**
	 * Deletes the data file of an information resource; other resource types have no data and are left alone. A
	 * failure is answered as a server error carrying file diagnostics for the log.
	 */
	public void deleteData(Entry entry) {
		if (entry.getResourceType() != ResourceType.InformationResource) {
			return;
		}
		Data data = (Data) entry.getResource();
		if (!data.delete()) {
			File dataFile = data.getDataFile();
			String diagnostics;
			if (dataFile == null) {
				diagnostics = "dataFile=null";
			} else {
				boolean exists = dataFile.exists();
				long size = exists ? dataFile.length() : -1;
				diagnostics = "path=" + dataFile.getAbsolutePath() + ", exists=" + exists + ", size=" + size;
			}
			throw new InternalServerErrorException("Unable to delete resource of entry " + entry.getEntryURI()
					+ " (" + diagnostics + ")");
		}
	}

	/** Thrown by {@link RequestBodyInputStream} once more than its limit has been read. */
	private static final class SizeLimitExceededException extends IOException {
	}

	/** Thrown by {@link RequestBodyInputStream} when the body cannot be read, typically as the client went away. */
	private static final class RequestBodyReadException extends IOException {
		RequestBodyReadException(IOException cause) {
			super(cause);
		}
	}

	/**
	 * Tells failures to read the body apart from failures to store it, and fails the read that takes the total
	 * past {@code maxBytes} (-1 for no limit), so a body without Content-Length is capped too.
	 */
	private static final class RequestBodyInputStream extends ProxyInputStream {

		private final long maxBytes;
		private long count;

		RequestBodyInputStream(InputStream in, long maxBytes) {
			super(in);
			this.maxBytes = maxBytes;
		}

		@Override
		protected void afterRead(int n) throws IOException {
			if (maxBytes != -1 && n > 0) {
				count += n;
				if (count > maxBytes) {
					throw new SizeLimitExceededException();
				}
			}
		}

		@Override
		protected void handleIOException(IOException e) throws IOException {
			// Also receives what afterRead throws.
			throw e instanceof SizeLimitExceededException ? e : new RequestBodyReadException(e);
		}
	}
}
