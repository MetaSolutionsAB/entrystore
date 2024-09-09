/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

	public URISplit(String anyURIStr, URL baseURL) {
		base = baseURL.toString();
		if (anyURIStr.startsWith(base)) {
			String withoutBase = anyURIStr.substring(base.length());
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

	private static String getBaseContextURI(String base, String contextId) {
		return base.concat(contextId);
	}

	public URI getContextURI() {
		return URI.create(getBaseContextURI(base, contextId));
	}

	public static URI createURI(String base, String contextId, String path, String entryId) {
		String uri = getBaseContextURI(base, contextId);

		if (path != null) {
			uri = uri.concat(SLASH_DELIMITER).concat(path);
		}

		if (entryId != null) {
			uri = uri.concat(SLASH_DELIMITER).concat(entryId);
		}

		return URI.create(uri);
	}

	public URI getContextMetaMetadataURI() {
		return createURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, contextId);
	}

	public URI getMetaMetadataURI() {
		return createURI(base, contextId, RepositoryProperties.ENTRY_PATH, id);
	}

	public URI getResourceURI() {
		return isContext
				? createURI(base, id, null, null)
				: createURI(base, contextId, RepositoryProperties.DATA_PATH, id);
	}
}
