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

package org.entrystore.repository.util;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.entrystore.repository.CorruptEntryException;

import java.net.URI;
import java.net.URL;

/**
 * Attributes failures caused by corrupt data to the entries whose data is corrupt.
 */
@UtilityClass
public class CorruptData {

	/**
	 * Tells whether a failure was caused by corrupt data of the given entry or of its context entry, i.e. whether the
	 * entry cannot be loaded because of the data it consists of. A failure caused by the corrupt data of another
	 * entry, e.g. of a principal loaded by an access check, is not.
	 * <p>
	 * The decision is made by the URI of the corrupt entry, not by the operation that failed: in ordinary operation an
	 * access check does not load an entry or its context entry again, since both are held in memory or in the soft
	 * cache, so a matching URI means that the entry's own data is corrupt.
	 *
	 * @param entryURI      the entry URI, or null, which never matches
	 * @param repositoryURL the base URL of the repository, to derive the context entry URI
	 */
	public static boolean isCorruptDataOf(Throwable failure, URI entryURI, URL repositoryURL) {
		return entryURI != null && CorruptEntryException.findIn(failure)
				.map(CorruptEntryException::getEntryURI)
				.filter(uri -> uri.equals(entryURI) || uri.equals(contextEntryURI(entryURI, repositoryURL)))
				.isPresent();
	}

	/**
	 * @return the message of the {@link CorruptEntryException} that caused a failure, which names the corrupt entry
	 * graph, or the root cause message if there is none, e.g. for a stack overflow
	 */
	public static String describe(Throwable failure) {
		return CorruptEntryException.findIn(failure).map(Throwable::getMessage)
				.orElseGet(() -> ExceptionUtils.getRootCauseMessage(failure));
	}

	/**
	 * @return the entry URI of the context of the given entry, or null if the URI does not denote an entry
	 */
	private static URI contextEntryURI(URI entryURI, URL repositoryURL) {
		try {
			URISplit split = new URISplit(entryURI, repositoryURL);
			return split.getUriType() == URIType.Unknown ? null : split.getContextMetaMetadataURI();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

}
