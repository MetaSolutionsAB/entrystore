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

import lombok.Getter;
import org.entrystore.impl.RepositoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

@Getter
public class URISplit {

	private static final Logger log = LoggerFactory.getLogger(URISplit.class);

	private static final String SLASH_DELIMITER = "/";
	private static final String URI_REGEX = "^_?[a-zA-Z0-9-_]+/?";
	private static final String URI_PARAMS_REGEX = "^_?[a-zA-Z0-9-_]+\\?\\S+";

	URIType uriType;
	String contextId;
	String id;
	String path;
	String base;
	boolean isContext = false;

	public URISplit(URI anyURI, URL baseURL) {

		if (isValidURI(anyURI)) {
			base = baseURL.toString();
			if (anyURI.toString().startsWith(base)) {
				String anyURIWithoutBase = anyURI.toString().substring(base.length());
				StringTokenizer st = new StringTokenizer(anyURIWithoutBase, SLASH_DELIMITER);
				contextId = st.nextToken();
				if (st.hasMoreTokens()) {
					path = st.nextToken();
					if (st.hasMoreTokens()) {
						id = st.nextToken();
					} else throw new IllegalArgumentException("URI is incompatible with EntryStore");
				} else if (anyURIWithoutBase.matches(URI_PARAMS_REGEX)) {
					uriType = URIType.Unknown;
					return;
				} else if (!anyURIWithoutBase.matches(URI_REGEX)) {
					throw new IllegalArgumentException("URI is malformed or encoded");
				} else {
					id = contextId;
					path = RepositoryProperties.DATA_PATH;
					contextId = RepositoryProperties.SYSTEM_CONTEXTS_ID;
					isContext = true;
				}

				if (path.equals(RepositoryProperties.ENTRY_PATH)) {
					uriType = URIType.MetaMetadata;
				} else if (path.equals(RepositoryProperties.MD_PATH)) {
					uriType = URIType.Metadata;
				} else {
					uriType = URIType.Resource;
				}
			} else {
				uriType = URIType.Unknown;
			}
		}
	}

	private static boolean isValidURI(URI uri) {
		if (uri == null) {
			throw new IllegalArgumentException("URI cannot be null");
		} else if (uri.getScheme() == null) {
			throw new IllegalArgumentException("URI is malformed or encoded");
		}

		return true;
	}

	private static String getBaseContextURIString(String base, String contextId) {
		if (base != null && contextId != null) {
			return base.concat(contextId);
		}

		return null;
	}

	public URI getContextURI() {
		String context = getBaseContextURIString(base, contextId);
		if (context != null) {
			return URI.create(context);

		}

		return null;
	}

	public URI getContextMetaMetadataURI() {
		return createURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, contextId);
	}

	public URI getMetaMetadataURI() {
		return createURI(base, contextId, RepositoryProperties.ENTRY_PATH, id);
	}

	public URI getMetadataURI() {
		return createURI(base, contextId, RepositoryProperties.MD_PATH, id);
	}

	/** The graph holding the entry's cached copy of external metadata; see {@link #getEntryGraphURIs()}. */
	public URI getCachedExternalMetadataURI() {
		return createURI(base, contextId, RepositoryProperties.EXTERNAL_MD_PATH, id);
	}

	/** The graph holding the entry's inverse relations; see {@link #getEntryGraphURIs()}. */
	public URI getRelationURI() {
		return createURI(base, contextId, RepositoryProperties.RELATION, id);
	}

	/**
	 * Every graph an entry occupies, for a caller that has to clean up an entry it cannot load.
	 *
	 * <p>Returned as one indivisible set rather than as five accessors a caller picks from: clearing a
	 * subset destroys the graph that <em>names</em> the rest and leaves the rest behind as unreachable
	 * data, which is the outcome such a caller is trying to avoid. The resource graph is the one most
	 * easily forgotten — {@code EntryImpl.remove} clears it through {@code resource.remove(rc)} rather
	 * than directly, and a {@code Link} does not have one, so a test written against a link cannot tell
	 * the difference.
	 *
	 * <p>Two things {@code EntryImpl.remove} does that no set of graph URIs can express: it deletes the
	 * on-disk data file of a {@code Data} entry, and it removes the inverse relations other contexts
	 * hold. A caller clearing these graphs directly leaves both behind.
	 */
	public Set<URI> getEntryGraphURIs() {
		// A LinkedHashSet rather than Set.of: these five are distinct for every URI this can be built
		// from, but Set.of throws on a duplicate, and the caller is a deletion path where an unexpected
		// IllegalArgumentException would abort a removal that has already begun.
		return new LinkedHashSet<>(List.of(getMetaMetadataURI(), getMetadataURI(),
				getCachedExternalMetadataURI(), getRelationURI(), getResourceURI()));
	}

	public URI getResourceURI() {

		return isContext
			? createURI(base, id)
			: createURI(base, contextId, RepositoryProperties.DATA_PATH, id);
	}

	public static URI createURI(String base, String contextId, String path, String entryId) {
		String uriString = getBaseContextURIString(base, contextId);

		if (uriString == null || path == null || entryId == null) {
			log.warn("Parameters must not be null or empty in uri={} path={} entryId={}.", uriString, path, entryId);
			// throw new IllegalArgumentException("Parameters must not be null or empty.");
		}

		URI uri = URI.create(uriString + SLASH_DELIMITER + path + SLASH_DELIMITER + entryId);

		if (isValidURI(uri)) {
			return uri;
		} else throw new IllegalArgumentException("URI is malformed or encoded");
	}

	public static URI createURI(String base, String contextId) {

		URI uri = URI.create(base.concat(contextId));

		if (isValidURI(uri)) {
			return uri;
		} else throw new IllegalArgumentException("URI is malformed or encoded");
	}
}
