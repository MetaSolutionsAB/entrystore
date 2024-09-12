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
import org.entrystore.Converter;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.net.URI;

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
}
