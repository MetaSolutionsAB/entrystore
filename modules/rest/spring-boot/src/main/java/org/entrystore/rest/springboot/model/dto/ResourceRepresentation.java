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

package org.entrystore.rest.springboot.model.dto;

import org.springframework.http.MediaType;

import java.io.File;
import java.util.Objects;

/**
 * What a GET on a resource URI answers with, as chosen by the service layer from the entry's type and the request
 * parameters. The controller maps each variant onto an HTTP response; the only representation decision it keeps is
 * the Content-Disposition type (inline vs attachment), driven by the {@code download} parameter and configuration.
 */
public sealed interface ResourceRepresentation {

	/**
	 * A binary resource served from disk. {@code sha256Digest} is the stored hex digest, or null when none exists.
	 */
	record FileDownload(File file, MediaType mediaType, String filename, String sha256Digest)
			implements ResourceRepresentation {

		public FileDownload {
			Objects.requireNonNull(file, "file must not be null");
			Objects.requireNonNull(mediaType, "mediaType must not be null");
			Objects.requireNonNull(filename, "filename must not be null");
		}
	}

	/**
	 * A serialized textual body: JSON, RDF, plain text or a syndication feed.
	 */
	record TextBody(String body, MediaType mediaType) implements ResourceRepresentation {

		public TextBody {
			Objects.requireNonNull(body, "body must not be null");
			Objects.requireNonNull(mediaType, "mediaType must not be null");
		}
	}

	/**
	 * A local binary entry for which no readable data file is available (nothing uploaded, or the resource type is
	 * not an information resource).
	 */
	record Empty() implements ResourceRepresentation {
	}
}
