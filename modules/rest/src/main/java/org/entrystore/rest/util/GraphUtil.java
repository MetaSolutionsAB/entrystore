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

package org.entrystore.rest.util;

import com.github.jsonldjava.sesame.SesameJSONLDParser;
import com.github.jsonldjava.sesame.SesameJSONLDWriter;
import info.aduna.xml.XMLReaderFactory;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.rio.ParserConfig;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.helpers.XMLParserSettings;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.rio.n3.N3Writer;
import org.openrdf.rio.ntriples.NTriplesParser;
import org.openrdf.rio.ntriples.NTriplesWriter;
import org.openrdf.rio.rdfxml.RDFXMLParser;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriter;
import org.openrdf.rio.trix.TriXParser;
import org.openrdf.rio.trix.TriXWriter;
import org.openrdf.rio.turtle.TurtleParser;
import org.openrdf.rio.turtle.TurtleWriter;
import org.restlet.data.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility methods to serialize and deserialize graphs.
 *
 * @author Hannes Ebner
 */
public class GraphUtil {

	private static Logger log = LoggerFactory.getLogger(GraphUtil.class);

	private static List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	private static ParserConfig safeXmlParserConfig;

	static {
		supportedMediaTypes.add(MediaType.APPLICATION_RDF_XML);
		supportedMediaTypes.add(MediaType.APPLICATION_JSON);
		supportedMediaTypes.add(MediaType.TEXT_RDF_N3);
		supportedMediaTypes.add(new MediaType(RDFFormat.TURTLE.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIX.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.NTRIPLES.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.TRIG.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType(RDFFormat.JSONLD.getDefaultMIMEType()));
		supportedMediaTypes.add(new MediaType("application/rdf+json"));
		safeXmlParserConfig = constructSafeXmlParserConfig();
	}

	/**
	 * @param graph  The Graph to be serialized.
	 * @param writer One of the following: N3Writer, NTriplesWriter,
	 *               RDFXMLPrettyWriter, RDFXMLWriter, TriGWriter, TriXWriter,
	 *               TurtleWriter
	 * @return A String representation of the serialized Graph.
	 */
	public static String serializeGraph(Graph graph, Class<? extends RDFWriter> writer) {
		if (graph == null || writer == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		StringWriter stringWriter = new StringWriter();
		RDFWriter rdfWriter = null;
		try {
			Constructor<? extends RDFWriter> constructor = writer.getConstructor(Writer.class);
			rdfWriter = (RDFWriter) constructor.newInstance(stringWriter);
		} catch (Exception e) {
			log.error(e.getMessage());
		}

		if (rdfWriter == null) {
			return null;
		}

		try {
			Map<String, String> namespaces = NS.getMap();
			for (String nsName : namespaces.keySet()) {
				rdfWriter.handleNamespace(nsName, namespaces.get(nsName));
			}
			rdfWriter.startRDF();
			for (Statement statement : graph) {
				rdfWriter.handleStatement(statement);
			}
			rdfWriter.endRDF();
		} catch (RDFHandlerException rdfe) {
			log.error(rdfe.getMessage());
		}
		return stringWriter.toString();
	}

	public static void serializeGraph(Graph graph, RDFWriter rdfWriter) {
		if (graph == null || rdfWriter == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		try {
			Map<String, String> namespaces = NS.getMap();
			for (String nsName : namespaces.keySet()) {
				rdfWriter.handleNamespace(nsName, namespaces.get(nsName));
			}
			rdfWriter.startRDF();
			for (Statement statement : graph) {
				rdfWriter.handleStatement(statement);
			}
			rdfWriter.endRDF();
		} catch (RDFHandlerException rdfe) {
			log.error(rdfe.getMessage());
		}
	}

	/**
	 * @param serializedGraph The Graph to be deserialized.
	 * @param parser          Instance of the following: N3Parser, NTriplesParser,
	 *                        RDFXMLParser, TriGParser, TriXParser, TurtleParser
	 * @return A String representation of the serialized Graph.
	 */
	public static Graph deserializeGraph(String serializedGraph, RDFParser parser) {
		try {
			return deserializeGraphUnsafe(serializedGraph, parser);
		} catch (RDFHandlerException | RDFParseException | IOException e) {
			log.error(e.getMessage());
			return null;
		}
	}

	public static Graph deserializeGraphUnsafe(String serializedGraph, RDFParser parser) throws RDFParseException, RDFHandlerException, IOException {
		if (serializedGraph == null || parser == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		StringReader reader = new StringReader(serializedGraph);
		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		parser.parse(reader, "");

		return new LinkedHashModel(collector.getStatements());
	}

	public static Graph deserializeGraph(String graphString, MediaType mediaType) {
		try {
			return deserializeGraphUnsafe(graphString, mediaType);
		} catch (RDFHandlerException | RDFParseException | IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public static Graph deserializeGraphUnsafe(String graphString, MediaType mediaType) throws RDFHandlerException, IOException, RDFParseException {
		Graph deserializedGraph = null;
		if (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.getName().equals("application/rdf+json")) {
			deserializedGraph = RDFJSON.rdfJsonToGraph(graphString);
		} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
			RDFXMLParser rdfXmlParser = new RDFXMLParser();
			rdfXmlParser.setParserConfig(safeXmlParserConfig);
			deserializedGraph = deserializeGraphUnsafe(graphString, rdfXmlParser);
		} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
			deserializedGraph = deserializeGraphUnsafe(graphString, new N3ParserFactory().getParser());
		} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraphUnsafe(graphString, new TurtleParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
			TriXParser trixParser = new TriXParser();
			trixParser.setParserConfig(safeXmlParserConfig);
			deserializedGraph = deserializeGraphUnsafe(graphString, trixParser);
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraphUnsafe(graphString, new NTriplesParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraphUnsafe(graphString, new TriGParser());
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraphUnsafe(graphString, new SesameJSONLDParser());
		}
		return deserializedGraph;
	}

	public static String serializeGraph(Graph graph, MediaType mediaType) {
		String serializedGraph = null;
		if (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.getName().equals("application/rdf+json")) {
			serializedGraph = RDFJSON.graphToRdfJson(graph);
		} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
			serializedGraph = serializeGraph(graph, RDFXMLPrettyWriter.class);
		} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
			serializedGraph = serializeGraph(graph, N3Writer.class);
		} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
			serializedGraph = serializeGraph(graph, TurtleWriter.class);
		} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
			serializedGraph = serializeGraph(graph, TriXWriter.class);
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			serializedGraph = serializeGraph(graph, NTriplesWriter.class);
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			serializedGraph = serializeGraph(graph, TriGWriter.class);
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			serializedGraph = serializeGraph(graph, SesameJSONLDWriter.class);
		} else {
			// fallback
			serializedGraph = serializeGraph(graph, TurtleWriter.class);
		}
		return serializedGraph;
	}

	public static boolean isSupported(MediaType mediaType) {
		for (MediaType mt : supportedMediaTypes) {
			if (mt.equals(mediaType, false)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Detects whether an RDF payload can be parsed by RDF4J.
	 *
	 * @param rdf The RDF to validate.
	 * @param mediaType The media type of the RDF.
	 * @return Returns null if successful or an error message if there was an error when parsing the payload.
	 */
	public static String validateRdf(String rdf, MediaType mediaType) {
		if (!isSupported(mediaType)) {
			return "Unsupported media type: " + mediaType;
		}

		StringReader reader = new StringReader(rdf);
		RDFHandler nullHandler = new URIValidatingRDFHandler();
		RDFParser parser = new RDFXMLParser();
		if (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.getName().equals("application/rdf+json")) {
			// we have special treatment of RDF/JSON here because it does not implement the Parser interface
			Graph g = RDFJSON.rdfJsonToGraph(rdf);
			if (g != null) {
				return "There was an error parsing the RDF/JSON payload";
			} else {
				return null;
			}
		} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
			parser = new N3ParserFactory().getParser();
		} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
			parser = new TurtleParser();
		} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
			parser = new TriXParser();
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			parser = new NTriplesParser();
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			parser = new TriGParser();
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			parser = new SesameJSONLDParser();
		}

		String error = null;
		try {
			parser.setRDFHandler(nullHandler);
			parser.parse(reader, "");
		} catch (RDFHandlerException | RDFParseException | IOException rdfe) {
			error = rdfe.getMessage();
		}

		if (error != null) {
			return error;
		}

		return null;
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
		}

		return pc;
	}

}