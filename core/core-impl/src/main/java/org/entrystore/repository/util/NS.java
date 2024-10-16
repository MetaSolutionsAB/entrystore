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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;

public class NS {

	private static final Logger log = LoggerFactory.getLogger(NS.class);

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

	public static String prov = "http://www.w3.org/ns/prov#";

	public static String owl = "http://www.w3.org/2002/07/owl#";

	public static String pubeu = "http://publications.europa.eu/resource/authority/";

	public static String geosparql = "http://www.opengis.net/ont/geosparql#";

	@Getter
	private static final HashMap<String, String> map;

	static {
		map = new HashMap<>();
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
		map.put("prov", NS.prov);
		map.put("owl", NS.owl);
		map.put("pubeu", NS.pubeu);
		map.put("schema", NS.schema);
		map.put("geosparql", NS.geosparql);
		map.put("skos", NS.skos);
	}

	public static URI expand(String abbreviatedURI) {
		if (!abbreviatedURI.contains(":")) {
			return URI.create(abbreviatedURI);
		}

		String[] uriSplits = abbreviatedURI.split(":");
		if (uriSplits.length != 2) {
			return URI.create(abbreviatedURI);
		}

		String namespace = uriSplits[0];
		if (NS.getMap().containsKey(namespace)) {
			return URI.create(NS.getMap().get(namespace) + uriSplits[1]);
		}

		try {
			return URI.create(abbreviatedURI);
		} catch (IllegalArgumentException e) {
			log.error("Could not create URI from: \"{}\"", abbreviatedURI);
			return null;
		}
	}

}
