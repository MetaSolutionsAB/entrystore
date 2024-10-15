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

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.entrystore.Converter;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OAIDC2RDFGraphConverterTest {

	private static final String entryURIString = "https://slashdot.org/12/entry/13";
	private static final String parentEntryURIString = "https://slashdot.org/12/entry/12";

	@Test
	public void convertToModel_null() {
		Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
		Object graph = oaiDcRdfConverter.convertToModel(null, URI.create(entryURIString));
		assertNull(graph);
	}

	@Test
	public void convertToModel_ok() {
		Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();

		try {
			Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
			Element element = document.createElement("root");
			Node child1 = document.createElement("ex:isChildOf");
			child1.setTextContent(parentEntryURIString);
			element.appendChild(child1);
			Model graph = oaiDcRdfConverter.convertToModel(element, URI.create(entryURIString));
			assertEquals(graph.toString(), "[(https://slashdot.org/12/entry/13, ex:isChildOf, \"https://slashdot.org/12/entry/12\") [null]]");
		} catch (ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void convertToModel_ok_oai() throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
		Converter oaiDcRdfConverter = new OAI_DC2RDFGraphConverter();
		XPathFactory factory = XPathFactory.newInstance();
		XPath xpath = factory.newXPath();
		xpath.setNamespaceContext(new NamespaceContext() {
			public String getNamespaceURI(String prefix) {
				return switch (prefix) {
					case "oai" -> "http://www.openarchives.org/OAI/2.0/";
					case "dc" -> "http://purl.org/dc/elements/1.1/";
					case "dcterms" -> "http://purl.org/dc/terms/";
					case "oai_dc" -> "http://www.openarchives.org/OAI/2.0/oai_dc/";
					case "rdn_dc" -> "http://www.rdn.ac.uk/oai/rdn_dc/";
					default -> null;
				};
			}

			public Iterator<String> getPrefixes(String val) {
				return null;
			}

			public String getPrefix(String uri) {
				return null;
			}
		});

		File file = new File("src/test/resources/oai.xml");
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		doc.getDocumentElement().normalize();
		Element getRecordElement = (Element) doc.getElementsByTagName("GetRecord").item(0);
		Element element = (Element) getRecordElement.getElementsByTagName("record").item(0);

		//XPathExpression expr = xpath.compile("oai:metadata/oai_dc:dc");
		//Node node = (Node) expr.evaluate(element, XPathConstants.NODE);

		Model graph = oaiDcRdfConverter.convertToModel(element, URI.create(entryURIString));
		int statementsCount = 0;
		String creator = null;
		String title = null;
		for (Statement statement: graph) {
			statementsCount++;
			if ("dc:creator".equals(statement.getPredicate().toString())) {
				creator = statement.getObject().stringValue();
			} else if ("dc:title".equals(statement.getPredicate().toString())) {
				title = statement.getObject().stringValue();
			}
		}

		assertEquals(10, statementsCount);
		assertEquals("Ebner, Hannes", creator);
		assertEquals("An information model", title);
	}
}
