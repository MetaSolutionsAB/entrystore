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

import static org.junit.jupiter.api.Assertions.*;

public class Graph2EntriesTest extends AbstractCoreTest {
	private final static Logger log = LoggerFactory.getLogger(Graph2EntriesTest.class);
	public static String personOntology = "http://www.example.org/person/";
	private static final ValueFactory valueFactory = SimpleValueFactory.getInstance();
	private static final IRI hasAge = valueFactory.createIRI(personOntology, "hasAge");
	private static final IRI hasIdentifierValue = valueFactory.createIRI(personOntology, "hasIdentifierValue");
	private static final IRI isOwnedBy = valueFactory.createIRI(personOntology, "isOwnedBy");

	private Context context;
	private static RDFXMLParser rdfXmlParser;

	@BeforeEach
	public void setUp() {
		super.setUp();
		rm.setCheckForAuthorization(false);

		rdfXmlParser = new RDFXMLParser();
		rdfXmlParser.setParserConfig(constructSafeXmlParserConfig());

		Entry entry = cm.createResource(null, GraphType.Context, null, null);
		context = (Context) entry.getResource();
	}

	@Test
	public void merge_null_create_and_update() throws IOException {
		String graphString = FileUtils.readFileToString(new File("src/test/resources/person-2mrids.owl"), "UTF-8");

		StringReader reader = new StringReader(graphString);
		StatementCollector collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		Model deserializedGraph = new LinkedHashModel(collector.getStatements());
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(deserializedGraph, null, null);
		Set<URI> resourcesCreated = context.getResources();

		assertEquals(2, entries.size());
		assertEquals(2, resourcesCreated.size());

		entries.forEach(entry -> {
			assertTrue(resourcesCreated.contains(entry.getResourceURI()));

			if (entry.getEntryURI().toString().contains("person")) {
				String age = entry.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
				String ageStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
				assertEquals(24, Integer.parseInt(age));
				assertEquals(age, ageStored);
			} else if (entry.getEntryURI().toString().contains("ssn")) {
				String ssn = entry.getMetadataGraph().getStatements(null, hasIdentifierValue, null).iterator().next().getObject().stringValue();
				String ssnStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasIdentifierValue, null).iterator().next().getObject().stringValue();
				assertEquals("8805139032", ssn);
				assertEquals(ssn, ssnStored);
			}
		});

		graphString = FileUtils.readFileToString(new File("src/test/resources/person-2mrids-update.owl"), "UTF-8");

		reader = new StringReader(graphString);
		collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		deserializedGraph = new LinkedHashModel(collector.getStatements());
		entries = g2e.merge(deserializedGraph, null, null);
		Set<URI> resourcesUpdated = context.getResources();

		assertEquals(2, entries.size());
		assertEquals(2, resourcesUpdated.size());

		entries.forEach(entry -> {
			assertTrue(resourcesUpdated.contains(entry.getResourceURI()));

			if (entry.getEntryURI().toString().contains("person")) {
				String age = entry.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
				String ageStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
				assertEquals(48, Integer.parseInt(age));
				assertEquals(age, ageStored);
			} else if (entry.getEntryURI().toString().contains("ssn")) {
				String ssn = entry.getMetadataGraph().getStatements(null, hasIdentifierValue, null).iterator().next().getObject().stringValue();
				String ssnStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasIdentifierValue, null).iterator().next().getObject().stringValue();
				assertEquals("9905139032", ssn);
				assertEquals(ssn, ssnStored);
			}
		});
	}

	/**
	 * Sending the same entity, even with different data, simply creates a new entity.
	 */
	@Test
	public void merge_empty_create_and_create() throws IOException {
		String graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid.owl"), "UTF-8");

		StringReader reader = new StringReader(graphString);
		StatementCollector collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		Model deserializedGraph = new LinkedHashModel(collector.getStatements());
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(deserializedGraph, "", null);
		Set<URI> resourcesCreated = context.getResources();

		assertEquals(1, entries.size());
		assertEquals(1, resourcesCreated.size());

		Entry entry = entries.iterator().next();
		assertTrue(resourcesCreated.contains(entry.getResourceURI()));

		String age = entry.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		String ageStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		assertEquals(24, Integer.parseInt(age));
		assertEquals(age, ageStored);

		graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid-update.owl"), "UTF-8");

		reader = new StringReader(graphString);
		collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		deserializedGraph = new LinkedHashModel(collector.getStatements());
		entries = g2e.merge(deserializedGraph, "", null);
		Set<URI> resourcesUpdated = context.getResources();

		assertEquals(1, entries.size());
		assertEquals(2, resourcesUpdated.size());

		Entry entryNew = entries.iterator().next();
		assertTrue(resourcesCreated.contains(entryNew.getResourceURI()));
		assertNotEquals(entry.getResourceURI(), entryNew.getResourceURI());

		age = entryNew.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		ageStored = context.getByEntryURI(entryNew.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		assertEquals(48, Integer.parseInt(age));
		assertEquals(age, ageStored);
	}

	@Test
	public void merge_non_existing_create_and_update() throws IOException {
		String graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid.owl"), "UTF-8");

		StringReader reader = new StringReader(graphString);
		StatementCollector collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		Model deserializedGraph = new LinkedHashModel(collector.getStatements());
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(deserializedGraph, "", null);
		Set<URI> resourcesCreated = context.getResources();

		assertEquals(1, entries.size());
		assertEquals(1, resourcesCreated.size());

		Entry entry = entries.iterator().next();
		assertTrue(resourcesCreated.contains(entry.getResourceURI()));

		String age = entry.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		String ageStored = context.getByEntryURI(entry.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		assertEquals(24, Integer.parseInt(age));
		assertEquals(age, ageStored);

		graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid-update.owl"), "UTF-8");

		reader = new StringReader(graphString);
		collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		deserializedGraph = new LinkedHashModel(collector.getStatements());
		entries = g2e.merge(deserializedGraph, entry.getId(), null);
		Set<URI> resourcesUpdated = context.getResources();

		assertEquals(1, entries.size());
		assertEquals(1, resourcesUpdated.size());

		Entry entryNew = entries.iterator().next();
		assertTrue(resourcesCreated.contains(entryNew.getResourceURI()));
		assertEquals(entry.getResourceURI(), entryNew.getResourceURI());

		age = entryNew.getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		ageStored = context.getByEntryURI(entryNew.getEntryURI()).getMetadataGraph().getStatements(null, hasAge, null).iterator().next().getObject().stringValue();
		assertEquals(48, Integer.parseInt(age));
		assertEquals(age, ageStored);
	}

	@Test
	public void merge_null_create_and_update_referenceId() throws IOException {
		String graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid.owl"), "UTF-8");

		StringReader reader = new StringReader(graphString);
		StatementCollector collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		Model deserializedGraph = new LinkedHashModel(collector.getStatements());
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(deserializedGraph, null, null);

		Entry entry = entries.iterator().next();

		graphString = FileUtils.readFileToString(new File("src/test/resources/person-1mrid-1rrid-update.owl"), "UTF-8");

		reader = new StringReader(graphString);
		collector = new StatementCollector();
		rdfXmlParser.setRDFHandler(collector);
		rdfXmlParser.parse(reader, "");

		deserializedGraph = new LinkedHashModel(collector.getStatements());
		entries = g2e.merge(deserializedGraph, null, null);
		Set<URI> resourcesUpdated = context.getResources();

		assertEquals(1, entries.size());
		assertEquals(2, resourcesUpdated.size());

		Entry entryNew = entries.iterator().next();
		assertTrue(resourcesUpdated.contains(entry.getResourceURI()));
		assertTrue(resourcesUpdated.contains(entryNew.getResourceURI()));

		String owner = entryNew.getMetadataGraph().getStatements(null, isOwnedBy, null).iterator().next().getObject().stringValue();
		String ownerStored = context.getByEntryURI(entryNew.getEntryURI()).getMetadataGraph().getStatements(null, isOwnedBy, null).iterator().next().getObject().stringValue();
		assertEquals(owner, ownerStored);
	}

	@Test
	public void merge_invalid_graph() {
		Graph2Entries g2e = new Graph2Entries(context);
		Set<Entry> entries = g2e.merge(null, "", null);

		assertNull(entries);
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
