/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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
import java.util.StringTokenizer;

@Getter
public class URISplit {

	private static final Logger log = LoggerFactory.getLogger(URISplit.class);

	private static final String SLASH_DELIMITER = "/";
	private static final String URI_REGEX = "^_?[a-zA-Z0-9-_]+/?";

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
