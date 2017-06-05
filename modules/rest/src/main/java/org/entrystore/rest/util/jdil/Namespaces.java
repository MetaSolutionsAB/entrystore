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


package org.entrystore.rest.util.jdil;

import java.util.HashMap;

public class Namespaces {
	HashMap<String, String> ns2base;
	Namespaces parent;
	public Namespaces(Namespaces parent, HashMap<String, String> ns2base) {
		if (ns2base == null) {
			this.ns2base = new HashMap<String, String>();
		} else {
			this.ns2base = ns2base;
		}
		this.parent = parent;
	}
	
	public String get(String ns) {
		String base = ns2base.get(ns);
		if (base == null && parent != null) {
			return parent.get(ns);
		}
		return base;
	}
	
	public String abbreviate(String uri) {
		for (String key : ns2base.keySet()) {
			String base = ns2base.get(key);
			if (uri.startsWith(base)) {
				return key + ":" + uri.substring(base.length());
			}
		}
		if (parent != null) {
			return parent.abbreviate(uri);
		}
		return uri;
	}
	
	public String expand(String nsUri) {
		if (!nsUri.matches("://")) {
			int colonLocation = nsUri.indexOf(':');
			if (colonLocation != -1) {
				String base = this.expandNS(nsUri.substring(0, colonLocation));
				if (base != null) {
					return base + nsUri.substring(colonLocation+1);
				}
			}
		}
		return nsUri;
	}
	
	public String expandNS(String ns) {
		String base = ns2base.get(ns);
		if (base != null) {
			return base;
		}
		if (parent != null) {
			return parent.expandNS(ns);
		}
		return null;
	}
}
