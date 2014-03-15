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


package org.entrystore.repository.impl;

import java.util.StringTokenizer;

import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.RepositoryProperties;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;


public class Util {
		
	public static StringTokenizer extractParameters(RepositoryManager rman, URI uri) {
		return extractParameters(rman, uri.toString());
	}

	public static StringTokenizer extractParameters(RepositoryManager rman, String uri) {
		String withoutBase = uri.substring(rman.getRepositoryURL().toString().length());
		return new StringTokenizer(withoutBase, "/");
	}
	
	public static URI getMetadataURI(EntryImpl item, String contextID, String uniqueID) {
		ValueFactory vf = item.getRepository().getValueFactory();
		String base = item.getRepositoryManager().getRepositoryURL().toString();
		return vf.createURI(base + contextID + "/" + RepositoryProperties.MD_PATH + "/" + uniqueID);
	}

	public static java.net.URI getMMdURIFromMdURI(URI md) {
		return java.net.URI.create(md.toString().replaceFirst("/"+RepositoryProperties.MD_PATH+"/", "/"+RepositoryProperties.ENTRY_PATH+"/"));
	}

	public static String getContextIdFromURI(RepositoryManager rman, java.net.URI sourceURI) {
		return extractParameters(rman, sourceURI.toString()).nextToken();
	}
	
	public static java.net.URI getContextURIFromURI(RepositoryManager rman, java.net.URI sourceURI) {
		return java.net.URI.create(rman.getRepositoryURL().toString()+ "/" + RepositoryProperties.SYSTEM_CONTEXTS_ID + "/" 
				+ RepositoryProperties.DATA_PATH + "/" + getContextIdFromURI(rman, sourceURI));
	}
	public static java.net.URI getContextMMdURIFromURI(RepositoryManager rman, java.net.URI sourceURI) {
		return java.net.URI.create(rman.getRepositoryURL().toString()+ "/" + RepositoryProperties.SYSTEM_CONTEXTS_ID + "/" 
				+ RepositoryProperties.ENTRY_PATH + "/" + getContextIdFromURI(rman, sourceURI));
	}
}
