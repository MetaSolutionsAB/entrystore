/**
 * Copyright (c) 2007-2010
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

package org.entrystore.repository.impl.converters;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.repository.Converter;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class OAI_DC2RDFGraphConverter implements Converter {

	private static Log log = LogFactory.getLog(OAI_DC2RDFGraphConverter.class);
	
	Map<String, Locale> localeMap;
	
	static {
		String[] languages = Locale.getISOLanguages();
		Map<String, Locale> localeMap = new HashMap<String, Locale>(languages.length);
		for (String language : languages) {
			Locale locale = new Locale(language);
			localeMap.put(locale.getISO3Language(), locale);
		}
	}

	/**
	 * Converts an oai_dc xml document tag metadata to a graph.
	 * 
	 * @param from
	 *            An XML NodeList.
	 * 
	 * @param resourceURI
	 *            Root URI of the resource's metadata.
	 * 
	 * @return the new metadata graph.
	 */
	public Object convert(Object from, java.net.URI resourceURI, java.net.URI metadataURI) {
		NodeList metadataList = null;

		if (from instanceof NodeList) {
			metadataList = (NodeList) from;
		} else if (from instanceof Node) {
			metadataList = ((Node) from).getChildNodes();
		} else {
			log.warn("Unable to convert object, class type not supported");
			return null;
		}

		Graph graph = new GraphImpl();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI root = vf.createURI(resourceURI.toString());

		for (int i = 0; i < metadataList.getLength(); i++) {
			Node n = metadataList.item(i);
			if (n == null || "#text".equals(n.getNodeName())) {
				continue;
			}

			String nodeNS = n.getNamespaceURI();
			String nodeName = n.getNodeName();
			String predicate = null;
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
				lit = vf.createLiteral(nodeContent, lang);
			} else {
				lit = vf.createLiteral(nodeContent);
			}
			
			graph.add(root, new org.openrdf.model.impl.URIImpl(predicate), lit);
		}

		return graph;
	}
	
	private String getISO2Language(String iso3Language) {
		if (localeMap == null) {
			String[] languages = Locale.getISOLanguages();
			localeMap = new HashMap<String, Locale>(languages.length);
			for (String language : languages) {
				Locale locale = new Locale(language);
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