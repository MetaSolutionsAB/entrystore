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

package org.entrystore.rest.springboot.util;

import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

import java.util.List;
import java.util.Optional;

@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class MultipartUtil {

	private static final String CONVENTIONAL_PART_NAME = "file";

	/**
	 * Returns the file part of a multipart request that carries a single upload, regardless of the
	 * name the client gave it.
	 * <p>
	 * Endpoints accepting a single upload do not use the part name for anything and no specification
	 * reserves the name "file" for it, so clients naming their part something else are accepted too.
	 * A part named "file" is preferred when it carries a filename, so that the conventional name wins
	 * over body order when a request carries several genuine file parts.
	 * <p>
	 * Parts whose filename is empty are considered last: an unfilled {@code <input type="file">} is
	 * still submitted, as a zero-byte part with {@code filename=""} that the servlet layer reports as
	 * a file part like any other, and such a placeholder must never win over a real upload.
	 * <p>
	 * Every part is considered, including several sharing one name, in the order Spring reports them,
	 * grouped by part name: {@link MultipartRequest#getMultiFileMap()} is keyed by name, so a body of
	 * a, b, a is seen as a, a, b. {@link MultipartRequest#getFileMap()} is deliberately not used: it
	 * is a single-value view that keeps only the first part of each name, which would hide the real
	 * upload behind an unfilled input of the same name.
	 *
	 * @param request the multipart request to read the file part from
	 * @return the first part named "file" carrying a filename, otherwise the first part carrying a
	 * filename, otherwise the first part carrying content; empty if the request has no file part or
	 * all of its file parts are empty placeholders
	 */
	public static Optional<MultipartFile> firstFilePart(MultipartRequest request) {
		List<MultipartFile> parts = request.getMultiFileMap().values().stream()
				.flatMap(List::stream)
				.toList();

		return parts.stream()
				.filter(part -> CONVENTIONAL_PART_NAME.equals(part.getName()) && carriesFilename(part))
				.findFirst()
				.or(() -> parts.stream().filter(MultipartUtil::carriesFilename).findFirst())
				.or(() -> parts.stream().filter(part -> !part.isEmpty()).findFirst());
	}

	private static boolean carriesFilename(MultipartFile part) {
		return StringUtils.isNotBlank(part.getOriginalFilename());
	}
}
