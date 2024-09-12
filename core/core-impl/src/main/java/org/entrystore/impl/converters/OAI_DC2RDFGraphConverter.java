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

package org.entrystore.impl.converters;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.entrystore.Converter;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;


public class OAI_DC2RDFGraphConverter implements Converter {

	private static final Log log = LogFactory.getLog(OAI_DC2RDFGraphConverter.class);

	static Map<String, Locale> localeMap;

	static {
		String[] languages = Locale.getISOLanguages();
		localeMap = new HashMap<>(languages.length);
		for (String language : languages) {
			Locale locale = new Locale.Builder().setLanguage(language).build();
			localeMap.put(locale.getISO3Language(), locale);
		}
	}

	/**
	 * Converts an oai_dc xml document tag metadata to a graph.
	 *
	 * @param from An XML Node.
	 * @param resourceURI Root URI of the resource's metadata.
	 * @return the new metadata graph Model.
	 */
	public Model convertToModel(Node from, URI resourceURI) {
		NodeList metadataList;

		if (from != null && from.getChildNodes().getLength() > 0) {
			metadataList = from.getChildNodes();
		} else {
			log.warn("Unable to convert Node to Model graph, as the Node is null or has empty childNodes");
			return null;
		}

		Model model = new LinkedHashModel();
		IRI root = iri(resourceURI.toString());

		for (int i = 0; i < metadataList.getLength(); i++) {
			Node n = metadataList.item(i);
			if (n == null || "#text".equals(n.getNodeName())) {
				continue;
			}

			String nodeNS = n.getNamespaceURI();
			String nodeName = n.getNodeName();
			String predicate;
			if (nodeName.contains(":") && (nodeNS != null)) {
				nodeName = nodeName.substring(nodeName.indexOf(":") + 1);
				predicate = nodeNS + nodeName;
			} else {
				predicate = nodeName;
			}
			String nodeContent = n.getTextContent();
			if (nodeContent == null) {
				continue;
			}
			nodeContent = nodeContent.trim();

			// fix to create a valid language literal with a 2-letter ISO code
			// this is about the language value as a literal, attributes are treated further down
			if ("language".equalsIgnoreCase(nodeName)) {
				// convert 3-letter to 2-letter ISO code
				if (nodeContent.length() == 3) {
					nodeContent = getISO2Language(nodeContent);

					if (nodeContent == null) {
						continue;
					}
				}
			}
			// <- fix

			// fix to convert ISO 3-letter lang codes to 2-letter codes
			// this is about LangStrings in general
			NamedNodeMap nodeAttributes = n.getAttributes();
			String lang = null;
			if (nodeAttributes != null) {
				Node langNode = nodeAttributes.getNamedItem("xml:lang");
				if (langNode != null) {
					lang = langNode.getNodeValue();
					if (lang != null) {
						lang = lang.trim();
						if (lang.length() == 3) {
							lang = getISO2Language(lang.toLowerCase());
						}
					}
				}
			}
			// <- fix

			Literal lit;
			if (lang != null) {
				lit = literal(nodeContent, lang);
			} else {
				lit = literal(nodeContent);
			}

			model.add(root, iri(predicate), lit);
		}

		return model;
	}

	private String getISO2Language(String iso3Language) {
		if (localeMap == null) {
			String[] languages = Locale.getISOLanguages();
			localeMap = new HashMap<>(languages.length);
			for (String language : languages) {
				Locale locale = new Locale.Builder().setLanguage(language).build();
				localeMap.put(locale.getISO3Language(), locale);
			}
		}

		Locale locale = localeMap.get(iso3Language);
		if (locale != null) {
			return locale.getLanguage();
		}

		return null;
	}

}
