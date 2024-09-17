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

import java.net.URI;
import java.net.URL;
import java.util.StringTokenizer;

@Getter
public class URISplit {

	private static final String SLASH_DELIMITER = "/";

	URIType uriType;
	String contextId;
	String id;
	String path;
	String base;
	boolean isContext = false;

	public URISplit(URI anyURI, URL baseURL) {
		this(anyURI.toString(), baseURL);
	}

	public URISplit(String anyURIString, URL baseURL) {
		if (baseURL != null) {
			base = baseURL.toString();
			if (anyURIString.startsWith(base)) {
				String withoutBase = anyURIString.substring(base.length());
				StringTokenizer st = new StringTokenizer(withoutBase, SLASH_DELIMITER);
				contextId = st.nextToken();
				if (st.hasMoreTokens()) {
					path = st.nextToken();
					id = st.nextToken();
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

	private static String getBaseContextURIString(String base, String contextId) {
		if (base != null && contextId != null) {
			return base.concat(contextId);
		}

		return null;
	}

	public URI getContextURI() {
		String context = getBaseContextURIString(base, contextId);
		return context != null ? URI.create(context) : null;
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
		String uri = getBaseContextURIString(base, contextId);

		// TODO: why do we allow uri with entryId /null ?
		// http://localhost/store/_contexts/entry/null
		return URI.create(uri + SLASH_DELIMITER + path + SLASH_DELIMITER + entryId);
	}

	public static URI createURI(String base, String contextId) {
		return URI.create(getBaseContextURIString(base, contextId));
	}
}
