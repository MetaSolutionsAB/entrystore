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

package org.entrystore.rest.standalone.springboot.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.common.xml.XMLReaderFactory;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings;
import org.eclipse.rdf4j.rio.helpers.JSONLDMode;
import org.eclipse.rdf4j.rio.helpers.JSONLDSettings;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.rio.jsonld.JSONLDParser;
import org.eclipse.rdf4j.rio.jsonld.JSONLDWriter;
import org.eclipse.rdf4j.rio.n3.N3ParserFactory;
import org.eclipse.rdf4j.rio.n3.N3Writer;
import org.eclipse.rdf4j.rio.ntriples.NTriplesParser;
import org.eclipse.rdf4j.rio.ntriples.NTriplesWriter;
import org.eclipse.rdf4j.rio.rdfxml.RDFXMLParser;
import org.eclipse.rdf4j.rio.rdfxml.util.RDFXMLPrettyWriter;
import org.eclipse.rdf4j.rio.trig.TriGParser;
import org.eclipse.rdf4j.rio.trig.TriGWriter;
import org.eclipse.rdf4j.rio.trix.TriXParser;
import org.eclipse.rdf4j.rio.trix.TriXWriter;
import org.eclipse.rdf4j.rio.turtle.TurtleParser;
import org.eclipse.rdf4j.rio.turtle.TurtleWriter;
import org.entrystore.repository.util.NS;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods to serialize and deserialize graphs.
 *
 * @author Hannes Ebner
 */
@Slf4j
public class GraphUtil {

	private static final Map<String, Class<? extends RDFWriter>> MEDIATYPE_TO_RDFWRITER_MAP = Map.of(
		RDFFormat.RDFXML.getDefaultMIMEType(), RDFXMLPrettyWriter.class,
		RDFFormat.N3.getDefaultMIMEType(), N3Writer.class,
		RDFFormat.TURTLE.getDefaultMIMEType(), TurtleWriter.class,
		RDFFormat.TRIX.getDefaultMIMEType(), TriXWriter.class,
		RDFFormat.NTRIPLES.getDefaultMIMEType(), NTriplesWriter.class,
		RDFFormat.TRIG.getDefaultMIMEType(), TriGWriter.class,
		RDFFormat.JSONLD.getDefaultMIMEType(), JSONLDWriter.class
	);

/*
	private final static List<MediaType> supportedMediaTypes = new ArrayList<>();

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
	}
*/

	/**
	 * @param graph  The Graph to be serialized.
	 * @param writer One of the following: N3Writer, NTriplesWriter,
	 *               RDFXMLPrettyWriter, RDFXMLWriter, TriGWriter, TriXWriter,
	 *               TurtleWriter
	 * @return A String representation of the serialized Graph.
	 */
	public static String serializeGraph(Model graph, Class<? extends RDFWriter> writer) {
		if (graph == null || writer == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		StringWriter stringWriter = new StringWriter();
		Map<String, String> namespaces = NS.getMap();
		RDFWriter rdfWriter = null;
		try {
			Constructor<? extends RDFWriter> constructor = writer.getConstructor(Writer.class);
			rdfWriter = constructor.newInstance(stringWriter);

			if (!System.getProperties().containsKey("org.eclipse.rdf4j.rio.rdf10_plain_literals")) {
				rdfWriter.getWriterConfig().set(BasicWriterSettings.XSD_STRING_TO_PLAIN_LITERAL, true);
			}
			if (!System.getProperties().containsKey("org.eclipse.rdf4j.rio.rdf10_language_literals")) {
				rdfWriter.getWriterConfig().set(BasicWriterSettings.RDF_LANGSTRING_TO_LANG_LITERAL, true);
			}
			if (!System.getProperties().containsKey("org.eclipse.rdf4j.rio.jsonld.optimize")) {
				rdfWriter.getWriterConfig().set(JSONLDSettings.OPTIMIZE, true);
			}
			if (!System.getProperties().containsKey("org.eclipse.rdf4j.rio.jsonld.use_native_types")) {
				rdfWriter.getWriterConfig().set(JSONLDSettings.USE_NATIVE_TYPES, true);
			}
			rdfWriter.getWriterConfig().set(JSONLDSettings.JSONLD_MODE, JSONLDMode.COMPACT);

			if (rdfWriter instanceof JSONLDWriter) {
				// we optimize to include only the used namespaces as contexts in JSON-LD
				namespaces = new HashMap<>();
				for (Statement s : graph) {
					namespaces.putAll(findNS(s.getSubject()));
					namespaces.putAll(findNS(s.getPredicate()));
					namespaces.putAll(findNS(s.getObject()));
				}
			}
		} catch (Exception e) {
			log.error(e.getMessage());
		}

		if (rdfWriter == null) {
			return null;
		}

		try {
			rdfWriter.startRDF();
			for (String nsName : namespaces.keySet()) {
				rdfWriter.handleNamespace(nsName, namespaces.get(nsName));
			}
			for (Statement statement : graph) {
				rdfWriter.handleStatement(statement);
			}
			rdfWriter.endRDF();
		} catch (RDFHandlerException rdfe) {
			log.error(rdfe.getMessage());
		}
		return stringWriter.toString();
	}

	public static void serializeGraph(Model graph, RDFWriter rdfWriter) {
		if (graph == null || rdfWriter == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		try {
			rdfWriter.startRDF();
			Map<String, String> namespaces = NS.getMap();
			for (String nsName : namespaces.keySet()) {
				rdfWriter.handleNamespace(nsName, namespaces.get(nsName));
			}
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
	public static Model deserializeGraph(String serializedGraph, RDFParser parser) {
		try {
			return deserializeGraphUnsafe(serializedGraph, parser);
		} catch (RDFHandlerException | RDFParseException | IOException e) {
			log.error(e.getMessage());
			return null;
		}
	}

	public static Model deserializeGraphUnsafe(String serializedGraph, RDFParser parser) throws RDFParseException, RDFHandlerException, IOException {
		if (serializedGraph == null || parser == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		StringReader reader = new StringReader(serializedGraph);
		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		parser.parse(reader, "");

		return new LinkedHashModel(collector.getStatements());
	}

	public static Model deserializeGraph(String graphString, String mediaType) {
		try {
			return deserializeGraphUnsafe(graphString, mediaType);
		} catch (RDFHandlerException | RDFParseException | IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public static Model deserializeGraphUnsafe(String graphString, String mediaType)
		throws RDFHandlerException, IOException, RDFParseException {

		if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType) || RDFFormat.RDFJSON.getDefaultMIMEType().equals(mediaType)) {
			return RDFJSON.rdfJsonToGraph(graphString);
		}

		RDFParser parser = createRdfParserForMediaType(mediaType);
		if (parser != null) {
			return deserializeGraphUnsafe(graphString, parser);
		} else {
			return null;
		}
	}

	private static RDFParser createRdfParserForMediaType(String mediaType) {
		RDFParser parser = null;
		if (RDFFormat.RDFXML.getDefaultMIMEType().equals(mediaType)) {
			parser = new RDFXMLParser();
			parser.setParserConfig(constructSafeXmlParserConfig());
		} else if (RDFFormat.N3.getDefaultMIMEType().equals(mediaType)) {
			parser = new N3ParserFactory().getParser();
		} else if (RDFFormat.TURTLE.getDefaultMIMEType().equals(mediaType)) {
			parser = new TurtleParser();
		} else if (RDFFormat.TRIX.getDefaultMIMEType().equals(mediaType)) {
			parser = new TriXParser();
			parser.setParserConfig(constructSafeXmlParserConfig());
		} else if (RDFFormat.NTRIPLES.getDefaultMIMEType().equals(mediaType)) {
			parser = new NTriplesParser();
		} else if (RDFFormat.TRIG.getDefaultMIMEType().equals(mediaType)) {
			parser = new TriGParser();
		} else if (RDFFormat.JSONLD.getDefaultMIMEType().equals(mediaType)) {
			parser = new JSONLDParser();
		}
		return parser;
	}

	public static String serializeGraph(Model graph, String mediaType) {
		if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType) || RDFFormat.RDFJSON.getDefaultMIMEType().equals(mediaType)) {
			return RDFJSON.graphToRdfJson(graph);
		}

		Class<? extends RDFWriter> writerClass = MEDIATYPE_TO_RDFWRITER_MAP.get(mediaType);
		if (writerClass == null) {
			// fallback - aligns with Restlet logic, but shouldn't we throw an IllegalArgumentException here?
			writerClass = TurtleWriter.class;
//			throw new IllegalArgumentException("No known RDFWriter for mediaType of '" + mediaType + "'. Allowed values: " + MEDIATYPE_TO_RDFWRITER_MAP.keySet());
		}

		return serializeGraph(graph, writerClass);
	}

	public static JSONObject serializeGraphToJson(Model graph, String rdfFormat) {
		if (rdfFormat == null || MediaType.APPLICATION_JSON_VALUE.equals(rdfFormat)) {
			// We don't use GraphUtil.serializeGraph() because we need a JSONObject here and
			// converting back and forth between String and JSONObject would not be very efficient
			return RDFJSON.graphToRdfJsonObject(graph);
		} else if (RDFFormat.JSONLD.getDefaultMIMEType().equals(rdfFormat)) {
			return new JSONObject(GraphUtil.serializeGraph(graph, rdfFormat));
		}
		log.warn("Model could not be serialized, returning empty JSON object");
		return new JSONObject();
	}

/*
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
	 * @param rdf       The RDF to validate.
	 * @param mediaType The media type of the RDF.
	 * @return Returns null if successful or an error message if there was an error when parsing the payload.
	 */
/*	public static String validateRdf(String rdf, MediaType mediaType) {
		if (!isSupported(mediaType)) {
			return "Unsupported media type: " + mediaType;
		}

		StringReader reader = new StringReader(rdf);
		RDFHandler nullHandler = new URIValidatingRDFHandler();
		RDFParser parser = new RDFXMLParser();
		parser.setParserConfig(constructSafeXmlParserConfig());
		if (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.getName().equals("application/rdf+json")) {
			// we have special treatment of RDF/JSON here because it does not implement the Parser interface
			Model g = RDFJSON.rdfJsonToGraph(rdf);
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
			parser.setParserConfig(constructSafeXmlParserConfig());
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			parser = new NTriplesParser();
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			parser = new TriGParser();
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			parser = new JSONLDParser();
		}

		String error = null;
		try {
			parser.setRDFHandler(nullHandler);
			parser.parse(reader, "");
		} catch (RDFHandlerException | RDFParseException | IOException rdfe) {
			error = rdfe.getMessage();
		}

		return error;
	}
*/

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

	private static Map<String, String> findNS(Value value) {
		Map<String, String> result = new HashMap<>();
		String dataTypeIri;
		if (value.isLiteral()) {
			// when Value is instance of Literal then .stringValue() returns e.g. "2024-11-18T17:11:59.147+01:00"^^<http://www.w3.org/2001/XMLSchema#dateTime>, so need add .getDataType()
			dataTypeIri = ((Literal) value).getDatatype().stringValue();
		} else if (value.isIRI()) {
			dataTypeIri = value.stringValue();
		} else {
			return result;
		}
		NS.getMap().forEach((prefix, ns) -> {
			if (dataTypeIri.startsWith(ns)) {
				result.put(prefix, ns);
			}
		});
		return result;
	}
}
