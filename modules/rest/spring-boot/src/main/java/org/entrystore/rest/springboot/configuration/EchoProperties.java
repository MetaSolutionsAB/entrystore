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

package org.entrystore.rest.springboot.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Bindings for {@code entrystore.echo.*}, consumed by {@code EchoService} to cap the payload
 * {@code POST /echo} will reflect and by {@code StatusService} to report that cap.
 *
 * <p>The default is the constant this key replaced, so existing deployments see no change. The cap is
 * checked against the already-spooled {@code MultipartFile}, so it bounds what is read back into
 * memory rather than what is accepted off the wire — {@code spring.servlet.multipart.max-file-size}
 * governs the latter and must stay at or above this value for the 413 to come from here rather than
 * from Jetty as a 400.
 */
@ConfigurationProperties(prefix = "entrystore.echo")
public record EchoProperties(@DefaultValue("10MB") DataSize maxFileSize) {

	/**
	 * Bounds per-request heap. {@code EchoService} reads the payload back as a UTF-8 {@code String} and
	 * the response then HTML-escapes it, so peak heap is a multiple of this cap rather than equal to it,
	 * on an endpoint that is reachable without authentication.
	 */
	private static final DataSize MAX_FILE_SIZE_CEILING = DataSize.ofMegabytes(64);

	public EchoProperties {
		if (maxFileSize == null || maxFileSize.toBytes() < 1) {
			throw new IllegalArgumentException(
					"entrystore.echo.max-file-size must be positive, got " + maxFileSize);
		}
		if (maxFileSize.compareTo(MAX_FILE_SIZE_CEILING) > 0) {
			// Ceiling spelled in MB rather than via DataSize.toString(), which renders raw bytes: the
			// operator writes this key as "64MB", so that is what the remedy should read as.
			throw new IllegalArgumentException("entrystore.echo.max-file-size must not exceed "
					+ MAX_FILE_SIZE_CEILING.toMegabytes() + "MB — the payload is held in memory and "
					+ "echoed back, got " + maxFileSize);
		}
	}
}
