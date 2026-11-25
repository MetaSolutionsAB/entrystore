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

import org.apache.commons.io.input.ProxyInputStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * Tells failures to read the body apart from failures to store it, and fails the read that takes the total
 * past {@code maxBytes} (-1 for no limit), so a body without Content-Length is capped too.
 */
final class RequestBodyInputStream extends ProxyInputStream {

	/** Thrown by {@link RequestBodyInputStream} once more than its limit has been read. */
	static final class SizeLimitExceededException extends IOException {
	}

	/** Thrown by {@link RequestBodyInputStream} when the body cannot be read, typically as the client went away. */
	static final class RequestBodyReadException extends IOException {
		RequestBodyReadException(IOException cause) {
			super(cause);
		}
	}

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
