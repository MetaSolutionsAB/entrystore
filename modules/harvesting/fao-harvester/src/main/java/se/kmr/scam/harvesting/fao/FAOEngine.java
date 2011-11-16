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

package se.kmr.scam.harvesting.fao;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.xerces.parsers.DOMParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import se.kmr.scam.harvesting.fao.FAOSubject.Scheme;

/**
 * Fetches metadata from FAO and parses the output into metadata objects.
 * 
 * @author Hannes Ebner
 */
public class FAOEngine {
	
	Logger log = LoggerFactory.getLogger(FAOEngine.class);
	
	private static String FAO_QUERY_URL = "http://www.fao.org/eims/secretariat/capacity_building/eims_search/advanced_s_result.asp";
	
	static {
		//ConverterManagerImpl.register("fao_xml", new FAO2RDFGraphConverter());
	}
	
	public List<Integer> getResourceList() {
		return getResourceList(null);
	}
	
	public List<Integer> getResourceList(Date fromDate) {
		List<Integer> result = new ArrayList<Integer>();
		try {
			String dateQuery = new String();
			if (fromDate != null) {
				dateQuery += "&dd_date=" + new SimpleDateFormat("dd/MM/yyyy").format(fromDate);
				dateQuery += "&fromtime=" + new SimpleDateFormat("HH:mm").format(fromDate);
			}
			URL url = new URL(FAO_QUERY_URL + "?minset=1&capacity=Y&institution=Y&language=en" + dateQuery);
			Reader isr = new InputStreamReader(url.openStream());
			InputSource is = new InputSource(isr);
			DOMParser p = new DOMParser();
			p.parse(is);
			Document doc = p.getDocument();
			Node n = doc.getDocumentElement().getFirstChild();
			while (n != null) {
				if (n.getNodeName().equals("publication")) {
					NamedNodeMap attrs = n.getAttributes();
					int len = attrs.getLength();
					for (int i = 0; i < len; i++) {
						Node attr = attrs.item(i);
						if (attr.getNodeName().equals("ID")) {
							result.add(Integer.valueOf(attr.getNodeValue()));
						}
					}
				}
				n = n.getNextSibling();
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		} catch (SAXException e) {
			log.error(e.getMessage());
		}
		return result;
	}
	
	public FAOMetadata getResource(int id) {
		FAOMetadata result = new FAOMetadata();
		result.setId(id);
		
		try {
			URL url = new URL(FAO_QUERY_URL + "?pub_id=" + id);
			result.setMetadataURL(url.toString());
			result.setSource(url.toString());
			Reader isr = new InputStreamReader(url.openStream());
			InputSource is = new InputSource(isr);
			DOMParser p = new DOMParser();
			p.parse(is);
			Document doc = p.getDocument();
			Node n = doc.getDocumentElement().getFirstChild();
			if (n != null && n.getNodeName().equals("publication")) {
				NodeList children = n.getChildNodes();
				int len = children.getLength();
				for (int i = 0; i < len; i++) {
					Node child = children.item(i);
					String name = child.getNodeName();
					String value = child.getTextContent();
					if ("title".equals(name)) {
						result.setTitle(value);
					} else if ("language".equals(name)) {
						result.setLanguage(value);
					} else if ("description".equals(name)) {
						result.setDescription(getChildValue(child, "abstract"));
					} else if ("creator".equals(name)) {
						result.setCreator(value);
					} else if ("pages".equals(name)) {
						try {
							result.setPages(Integer.parseInt(value));
						} catch (NumberFormatException nfe) {
							result.setPages(-1);
						}
					} else if ("date".equals(name)) {
						if (isCurrentAttribute(child, "YEAR")) {
							result.setYear(value);
						}
					} else if ("identifier".equals(name)) {
						if (isCurrentAttribute(child, "URI")) {
							result.setUri(value);
						} else if (isCurrentAttribute(child, "PDF_URI")) {
							result.setPdfURI(value);
						} else if (isCurrentAttribute(child, "JOB_NO")) {
							result.setJobNr(value);	
						}
					} else if ("subject".equals(name)) {
						result.setSubjects(getSubjects(child));
					}
				}
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		} catch (SAXException e) {
			log.error(e.getMessage());
		}
		
		return result;
	}
	
	public List<FAOSubject> getSubjects(Node node) {
		List<FAOSubject> result = new ArrayList<FAOSubject>();
		
		NodeList nodes = node.getChildNodes();
		int len = nodes.getLength();
		for (int i = 0; i < len; i++) {
			Node n = nodes.item(i);
			if (!n.getNodeName().equals("subjectThesaurus")) {
				continue;
			}
			FAOSubject s = new FAOSubject();
			NamedNodeMap attrs = n.getAttributes();
			int attrLen = attrs.getLength();
			for (int j = 0; j < attrLen; j++) {
				Node attr = attrs.item(j);
				String name = attr.getNodeName();
				String value = attr.getNodeValue();
				
				if ("scheme".equals(name)) {
					if ("AGROVOC".equals(value)) {
						s.setScheme(Scheme.AGROVOC);
					} else if ("AREA".equals(value)) {
						s.setScheme(Scheme.AREA);
					} else if ("INFOTYPE".equals(value)) {
						s.setScheme(Scheme.INFOTYPE);
					} else if ("MEDIA".equals(value)) {
						s.setScheme(Scheme.MEDIA);
					} else if ("TARGET".equals(value)) {
						s.setScheme(Scheme.TARGET);
					}
				} else if ("xml:lang".equals(name)) {
					s.setLanguage(value);
				} else if ("ID".equals(name)) {
					s.setId(Integer.parseInt(value));
				} else if ("name".equals(name)) {
					s.setName(value);
				}
			}
			s.setSubject(n.getTextContent());
			result.add(s);
		}
		
		return result;
	}
	
	public List<FAOMetadata> getResources() {
		List<Integer> resourceIDs = getResourceList();
		List<FAOMetadata> result = new ArrayList<FAOMetadata>();
		for (Integer id : resourceIDs) {
			result.add(getResource(id));
		}
		return result;
	}
	
	private String getChildValue(Node node, String childName) {
		NodeList nodes = node.getChildNodes();
		int len = nodes.getLength();
		for (int i = 0; i < len; i++) {
			Node n = nodes.item(i);
			if (n.getNodeName().equals(childName)) {
				return n.getTextContent();
			}
		}
		return null;
	}
	
	private boolean isCurrentAttribute(Node node, String attribute) {
		NamedNodeMap attrs = node.getAttributes();
		int len = attrs.getLength();
		for (int i = 0; i < len; i++) {
			Node attr = attrs.item(i);
			if (attr.getNodeValue().equals(attribute)) {
				return true;
			}
		}
		return false;
	}
	
	public static void main(String[] argv) {
		FAOEngine harvester = new FAOEngine();
		
		harvester.getResourceList();
		
//		List<Integer> resourceIDs = harvester.getResourceList();
//		for (Integer id : resourceIDs) {
//			System.out.println(id);
//		}
//		System.out.println("Number of available resources: " + resourceIDs.size());
		
//		for (int i = 0; i < 5; i++) {
//			System.out.println(harvester.getResource(resourceIDs.get(i)));
//		}
		
		System.out.println(harvester.getResource(242424));
		System.out.println(harvester.getResourceList().size());
	}

}