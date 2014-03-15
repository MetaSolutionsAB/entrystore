/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import java.net.URI;
import java.net.URL;
import java.util.StringTokenizer;

import org.entrystore.impl.RepositoryProperties;

public class URISplit{

	public enum URIType {
		Resource,
		Metadata,
		MetaMetadata,
		Unknown
	}

	URIType ut;
	String contextId;
	String id;
	String path;
	String base;
	boolean isContext = false;

	public URISplit(org.openrdf.model.URI anyURI, URL baseURL) {
		this(anyURI.toString(), baseURL);
	}

	public URISplit(java.net.URI anyURI, URL baseURL) {
		this(anyURI.toString(), baseURL);
	}
	
	public URISplit(String anyURIStr, URL baseURL) {
		base = baseURL.toString();
		if (anyURIStr.startsWith(base)) {
			String withoutBase = anyURIStr.substring(base.toString().length());
			StringTokenizer st = new StringTokenizer(withoutBase, "/");
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
				ut = URIType.MetaMetadata;
			} else if (path.equals(RepositoryProperties.MD_PATH)) {
				ut = URIType.Metadata;
			} else {
				ut = URIType.Resource;
			}
		} else {
			ut = URIType.Unknown;
		}
	}
	
	public URI getMetaMetadataURI() {
		return fabricateURI(base, contextId, RepositoryProperties.ENTRY_PATH,id);
	}

	public URI getMetadataURI() {
		return fabricateURI(base, contextId, RepositoryProperties.MD_PATH, id);
	}
	
	public URI getResourceURI() {
		if (isContext) {
			return fabricateContextURI(base, id);
		} else {
			return fabricateURI(base, contextId, RepositoryProperties.DATA_PATH, id);
		}
	}
	
	public String getContextID() {
		return contextId;
	}

	public URI getContextMetaMetadataURI() {
		return fabricateURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.ENTRY_PATH, contextId);		
	}

	public URI getContextMetadataURI() {
		return fabricateURI(base, RepositoryProperties.SYSTEM_CONTEXTS_ID, RepositoryProperties.MD_PATH, contextId);		
	}

	public URI getContextURI() {
		return URI.create(base + contextId);
	}

	public URIType getURIType() {
		return ut;
	}
	
	public String getID() {
		return id;
	}
		
	public static URI fabricateURI(String base, String contextId, String path, String entryId) {
		return URI.create(base + contextId + "/" + path + "/" + entryId);		
	}

	public static URI fabricateContextURI(String base, String contextId) {
		return URI.create(base + contextId);		
	}
}