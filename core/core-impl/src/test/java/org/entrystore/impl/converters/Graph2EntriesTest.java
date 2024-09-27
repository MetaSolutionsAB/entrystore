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

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.common.xml.XMLReaderFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.rio.rdfxml.RDFXMLParser;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.impl.AbstractCoreTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Graph2EntriesTest extends AbstractCoreTest {
	private final static Logger log = LoggerFactory.getLogger(Graph2EntriesTest.class);
	public static String personOntology = "http://www.example.org/person/";
	private static final ValueFactory valueFactory = SimpleValueFactory.getInstance();
	private static final IRI hasAge = valueFactory.createIRI(personOntology, "hasAge");
	private static final IRI hasIdentifierValue = valueFactory.createIRI(personOntology, "hasIdentifierValue");

	private Context context;

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		// A new Context
		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) entry.getResource();
	}

	@Test
	public void merge_rdf_create() throws IOException {
		File file = new File("src/test/resources/person.owl");
		String graphString = FileUtils.readFileToString(file, "UTF-8");
		RDFXMLParser rdfXmlParser = new RDFXMLParser();
		rdfXmlParser.setParserConfig(constructSafeXmlParserConfig());

		StringReader reader = new StringReader(graphString);
		StatementCollector collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		Model deserializedGraph = new LinkedHashModel(collector.getStatements());
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(deserializedGraph, null, null);
		Set<URI> resources = context.getResources();

		assertEquals(2, entries.size());
		assertEquals(2, resources.size());

		entries.forEach(entry -> {
			assertTrue(resources.contains(entry.getResourceURI()));

			if (entry.getEntryURI().toString().contains("person")) {
				String age = entry.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
				assertEquals(24, Integer.parseInt(age));
			}
			else if (entry.getEntryURI().toString().contains("ssn")) {
				String ssn = entry.getMetadataGraph().getStatements(null, hasIdentifierValue, null).iterator().next().getObject().stringValue();
				assertEquals("8805139032", ssn);
			}
		});
	}

	/**
	 * Builds a custom and safe XML parser configuration to prevent XXE attacks. Creates a custom
	 * XML reader to be able to set features that are not supported by the reader which is initialized by Sesame.
	 *
	 * @return Returns a custom XML parser configuration including a custom XML reader.
	 */
	private static ParserConfig constructSafeXmlParserConfig() {
		ParserConfig pc = new ParserConfig();
		pc.set(XMLParserSettings.LOAD_EXTERNAL_DTD, false);
		pc.set(XMLParserSettings.SECURE_PROCESSING, true);

		XMLReader customXmlReader = null;
		try {
			customXmlReader = XMLReaderFactory.createXMLReader();
		} catch (SAXException e) {
			log.error(e.getMessage());
		}

		if (customXmlReader != null) {
			pc.set(XMLParserSettings.CUSTOM_XML_READER, customXmlReader);
			try {
				// Disallow DOCTYPE declaration
				customXmlReader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			} catch (SAXException se) {
				log.warn(se.getMessage());
			}
			try {
				// External text entities
				customXmlReader.setFeature("http://xml.org/sax/features/external-general-entities", false);
			} catch (SAXException se) {
				log.warn(se.getMessage());
			}
			try {
				// External parameter entities
				customXmlReader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			} catch (SAXException se) {
				log.warn(se.getMessage());
			}
			try {
				// Disable external DTDs
				customXmlReader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			} catch (SAXException se) {
				log.warn(se.getMessage());
			}
		}

		return pc;
	}
}
