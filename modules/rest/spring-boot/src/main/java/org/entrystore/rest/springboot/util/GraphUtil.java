/*
 * Copyright (c) 2007-2026 MetaSolutions AB
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

package org.entrystore.rest.springboot.util;

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
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utility methods to serialize and deserialize graphs.
 *
 * @author Hannes Ebner
 */
@Slf4j
public class GraphUtil {

	/** Default media type for RDF responses when neither a format parameter nor an Accept header selects one. */
	public static final String DEFAULT_RDF_MEDIA_TYPE = "application/rdf+xml";

	private static final String LEGACY_N3_MEDIA_TYPE = "text/rdf+n3";

	private static final Map<String, Class<? extends RDFWriter>> MEDIATYPE_TO_RDFWRITER_MAP = Map.of(
			RDFFormat.RDFXML.getDefaultMIMEType(), RDFXMLPrettyWriter.class,
			RDFFormat.N3.getDefaultMIMEType(), N3Writer.class,
			RDFFormat.TURTLE.getDefaultMIMEType(), TurtleWriter.class,
			RDFFormat.TRIX.getDefaultMIMEType(), TriXWriter.class,
			RDFFormat.NTRIPLES.getDefaultMIMEType(), NTriplesWriter.class,
			RDFFormat.TRIG.getDefaultMIMEType(), TriGWriter.class,
			RDFFormat.JSONLD.getDefaultMIMEType(), JSONLDWriter.class
	);

	private static final Set<String> ALLOWED_RDF_MEDIA_TYPES;

	static {
		Set<String> types = new HashSet<>(MEDIATYPE_TO_RDFWRITER_MAP.keySet());
		types.add(MediaType.APPLICATION_JSON_VALUE);
		types.add(RDFFormat.RDFJSON.getDefaultMIMEType());
		ALLOWED_RDF_MEDIA_TYPES = Set.copyOf(types);
	}

	/**
	 * RFC 7231 Section 5.3.2 content negotiation ordering:
	 * quality value (q) descending as primary key, specificity as tiebreaker.
	 */
	static final Comparator<MediaType> QUALITY_THEN_SPECIFICITY =
			Comparator.comparingDouble(MediaType::getQualityValue).reversed()
					.thenComparing((a, b) -> {
						if (a.isMoreSpecific(b)) return -1;
						if (b.isMoreSpecific(a)) return 1;
						return 0;
					});

	/**
	 * Normalizes the legacy text/rdf+n3 MIME type to its standard equivalent
	 * (text/n3, as returned by RDF4J's {@link RDFFormat#N3}).
	 */
	static String normalizeLegacyMediaType(String mediaType) {
		if (LEGACY_N3_MEDIA_TYPE.equalsIgnoreCase(mediaType)) {
			return RDFFormat.N3.getDefaultMIMEType();
		}
		return mediaType;
	}

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

		writeGraph(graph, rdfWriter, namespaces);
		return stringWriter.toString();
	}

	public static void serializeGraph(Model graph, RDFWriter rdfWriter) {
		if (graph == null || rdfWriter == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		writeGraph(graph, rdfWriter, NS.getMap());
	}

	private static void writeGraph(Model graph, RDFWriter rdfWriter, Map<String, String> namespaces) {
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
			log.error("Failed to serialize RDF graph: {}", rdfe.getMessage(), rdfe);
		}
	}

	/**
	 * Deserializes an RDF body into an in-memory {@link Model}. JSON ({@code application/json})
	 * and RDF/JSON ({@code application/rdf+json}) bodies are routed through {@link RDFJSON};
	 * all other supported types are parsed via the matching RDF4J {@link RDFParser}. The legacy
	 * {@code text/rdf+n3} alias is normalized to {@code text/n3} before lookup.
	 *
	 * @param graphString the serialized RDF body; must not be {@code null}
	 * @param mediaType   the media type of the body (e.g. {@code text/turtle},
	 *                    {@code application/ld+json}); used to select the parser
	 * @return the parsed graph, never {@code null}
	 * @throws BadRequestException if the body cannot be parsed or the media type is unsupported
	 */
	public static Model deserializeGraph(String graphString, String mediaType) {
		String normalized = normalizeLegacyMediaType(mediaType);

		if (MediaType.APPLICATION_JSON_VALUE.equals(normalized) || RDFFormat.RDFJSON.getDefaultMIMEType().equals(normalized)) {
			try {
				Model graph = RDFJSON.rdfJsonToGraph(graphString);
				if (graph == null) {
					throw new BadRequestException("Malformed RDF/JSON in request body");
				}
				return graph;
			} catch (RDFParseException e) {
				throw new BadRequestException("Malformed RDF/JSON in request body", e);
			}
		}

		RDFParser parser = createRdfParserForMediaType(normalized);
		if (parser == null) {
			throw new BadRequestException("Unsupported RDF media type: " + mediaType);
		}
		try {
			return parseWith(parser, graphString);
		} catch (RDFParseException e) {
			throw new BadRequestException("Malformed RDF in request body", e);
		} catch (RDFHandlerException | IOException e) {
			throw new BadRequestException("Unable to process the RDF graph from the request body", e);
		}
	}

	private static Model parseWith(RDFParser parser, String graphString)
			throws RDFParseException, RDFHandlerException, IOException {
		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		parser.parse(new StringReader(graphString), "");
		return new LinkedHashModel(collector.getStatements());
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

	public static Class<? extends RDFWriter> getRDFWriterClassForMediaType(String mediaType) {
		if (mediaType == null) {
			return null;
		}
		return MEDIATYPE_TO_RDFWRITER_MAP.get(normalizeLegacyMediaType(mediaType));
	}

	public static String validateRdfMediaType(String mediaType) {
		return validateRdfMediaType(mediaType, HttpStatus.NOT_ACCEPTABLE);
	}

	public static String validateRdfMediaType(String mediaType, HttpStatus rejectStatus) {
		if (mediaType == null) {
			throw new CustomResponseException("Unsupported media type", rejectStatus);
		}
		String normalized = normalizeLegacyMediaType(mediaType).toLowerCase(Locale.ROOT);
		if (!ALLOWED_RDF_MEDIA_TYPES.contains(normalized)) {
			throw new CustomResponseException("Unsupported media type", rejectStatus);
		}
		return normalized;
	}

	public static String resolveAcceptedMediaType(String acceptHeader, String defaultMediaType) {
		if (acceptHeader == null || acceptHeader.isBlank()) {
			return defaultMediaType;
		}

		try {
			List<MediaType> acceptTypes = MediaType.parseMediaTypes(acceptHeader);
			acceptTypes.sort(QUALITY_THEN_SPECIFICITY);
			for (MediaType type : acceptTypes) {
				if (type.isWildcardType() || type.isWildcardSubtype()) {
					return defaultMediaType;
				}
				String typeStr = normalizeLegacyMediaType(type.getType() + "/" + type.getSubtype())
						.toLowerCase(Locale.ROOT);
				if (ALLOWED_RDF_MEDIA_TYPES.contains(typeStr)) {
					return typeStr;
				}
			}
		} catch (InvalidMediaTypeException e) {
			log.warn("Failed to parse Accept header '{}': {}", acceptHeader, e.getMessage());
			throw new CustomResponseException("Malformed Accept header", HttpStatus.NOT_ACCEPTABLE);
		}

		throw new CustomResponseException("Unsupported media type", HttpStatus.NOT_ACCEPTABLE);
	}

	public static String serializeGraph(Model graph, String mediaType) {
		mediaType = normalizeLegacyMediaType(mediaType);
		if (MediaType.APPLICATION_JSON_VALUE.equals(mediaType) || RDFFormat.RDFJSON.getDefaultMIMEType().equals(mediaType)) {
			return RDFJSON.graphToRdfJson(graph);
		}

		Class<? extends RDFWriter> writerClass = getRDFWriterClassForMediaType(mediaType);
		if (writerClass == null) {
			throw new IllegalArgumentException("No known RDFWriter for mediaType of '" + mediaType + "'. Allowed values: " + MEDIATYPE_TO_RDFWRITER_MAP.keySet());
		}

		return serializeGraph(graph, writerClass);
	}

	public static JSONObject serializeGraphToJson(Model graph, String rdfFormat) {
		if (rdfFormat == null || MediaType.APPLICATION_JSON_VALUE.equals(rdfFormat)) {
			// We don't use GraphUtil.serializeGraph() because we need a JSONObject here and
			// converting back and forth between String and JSONObject would not be very efficient
			return RDFJSON.graphToRdfJsonObject(graph);
		}
		rdfFormat = normalizeLegacyMediaType(rdfFormat);
		if (RDFFormat.JSONLD.getDefaultMIMEType().equals(rdfFormat)) {
			return new JSONObject(GraphUtil.serializeGraph(graph, rdfFormat));
		}
		log.warn("Model could not be serialized, returning empty JSON object");
		return new JSONObject();
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
