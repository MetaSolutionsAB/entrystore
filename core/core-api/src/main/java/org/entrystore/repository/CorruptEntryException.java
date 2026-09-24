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

package org.entrystore.repository;

import lombok.Getter;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.net.URI;
import java.util.Optional;

/**
 * Thrown when an entry cannot be loaded because its data in the store is corrupt, as opposed to a failure of the
 * store itself. Loading such an entry fails the same way until the data is repaired.
 */
@Getter
public class CorruptEntryException extends RepositoryException {

	/**
	 * The URI of the entry whose data is corrupt, which is not necessarily the entry that was requested: loading an
	 * entry can load further entries, e.g. its context entry.
	 */
	private final URI entryURI;

	public CorruptEntryException(URI entryURI, String message) {
		super(message);
		this.entryURI = entryURI;
	}

	public CorruptEntryException(URI entryURI, String message, Exception cause) {
		super(message, cause);
		this.entryURI = entryURI;
	}

	/**
	 * @return the first {@code CorruptEntryException} in the cause chain of the given throwable
	 */
	public static Optional<CorruptEntryException> findIn(Throwable throwable) {
		return Optional.ofNullable(ExceptionUtils.throwableOfType(throwable, CorruptEntryException.class));
	}

}
