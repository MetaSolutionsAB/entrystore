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

import java.util.HashMap;
import java.util.Map;

public class NS {

	public static String dc = "http://purl.org/dc/elements/1.1/";

	public static String dcterms = "http://purl.org/dc/terms/";

	public static String foaf = "http://xmlns.com/foaf/0.1/";

	public static String lom = "http://ltsc.ieee.org/rdf/lomv1p0/lom#";

	public static String lomvoc = "http://ltsc.ieee.org/rdf/lomv1p0/vocabulary#";
	
	public static String lomterms = "http://ltsc.ieee.org/rdf/lomv1p0/terms#";

	public static String lrevoc = "http://organic-edunet.eu/LOM/rdf/voc#";
	
	public static String oe = "http://organic-edunet.eu/LOM/rdf/";

	public static String rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

	public static String rdfs = "http://www.w3.org/2000/01/rdf-schema#";

	public static String entrystore = "http://entrystore.org/terms/";

	public static String xsd = "http://www.w3.org/2001/XMLSchema#";
	
	public static String vcard_old = "http://www.w3.org/2001/vcard-rdf/3.0#";

	public static String vcard = "http://www.w3.org/2006/vcard/ns#fn";

	/**
	 * @return A map with all relevant namespaces. Key is name and Value is namespace.
	 */
	public static Map<String, String> getMap() {
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("dc", NS.dc);
		map.put("dcterms", NS.dcterms);
		map.put("foaf", NS.foaf);
		map.put("rdf", NS.rdf);
		map.put("rdfs", NS.rdfs);
		map.put("xsd", NS.xsd);
		map.put("es", NS.entrystore);
		return map;
	}
	
}