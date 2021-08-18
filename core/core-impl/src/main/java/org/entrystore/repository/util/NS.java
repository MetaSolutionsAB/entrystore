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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class NS {

	public static String dc = "http://purl.org/dc/elements/1.1/";

	public static String dcterms = "http://purl.org/dc/terms/";

	public static String foaf = "http://xmlns.com/foaf/0.1/";

	public static String rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

	public static String rdfs = "http://www.w3.org/2000/01/rdf-schema#";

	public static String entrystore = "http://entrystore.org/terms/";

	public static String xsd = "http://www.w3.org/2001/XMLSchema#";
	
	public static String vcard = "http://www.w3.org/2006/vcard/ns#";

	public static String adms = "http://www.w3.org/ns/adms#";

	public static String dcat = "http://www.w3.org/ns/dcat#";

	public static String odrs = "http://schema.theodi.org/odrs#";

    public static String skos = "http://www.w3.org/2004/02/skos/core#";

    public static String schema = "http://schema.org/";

    private static HashMap<String, String> map;

	static {
		map = new HashMap<String, String>();
		map.put("dc", NS.dc);
		map.put("dcterms", NS.dcterms);
		map.put("foaf", NS.foaf);
		map.put("rdf", NS.rdf);
		map.put("rdfs", NS.rdfs);
		map.put("xsd", NS.xsd);
		map.put("es", NS.entrystore);
		map.put("dcat", NS.dcat);
		map.put("adms", NS.adms);
		map.put("odrs", NS.odrs);
		map.put("vcard", NS.vcard);
	}

	/**
	 * @return A map with all relevant namespaces. Key is name and Value is namespace.
	 */
	public static Map<String, String> getMap() {
		return map;
	}

	public static URI expand(String abbrvURI) {
		if (!abbrvURI.contains(":")) {
			return URI.create(abbrvURI);
		}
		String[] uriS = abbrvURI.split(":");
		if (uriS.length != 2) {
			return URI.create(abbrvURI);
		}
		String ns = uriS[0];
		if (NS.getMap().containsKey(ns)) {
			return URI.create(NS.getMap().get(ns) + uriS[1]);
		}
		return URI.create(abbrvURI);
	}
	
}