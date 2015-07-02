/*
 * Copyright (c) 2007-2015 MetaSolutions AB
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
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * Utility methods to serialize and deserialize graphs.
 *
 * @author Hannes Ebner
 */
public class GraphUtil {

	private static Logger log = LoggerFactory.getLogger(GraphUtil.class);


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
		if (serializedGraph == null || parser == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		StringReader reader = new StringReader(serializedGraph);
		StatementCollector collector = new StatementCollector();
		try {
			parser.setRDFHandler(collector);
			parser.parse(reader, "");
		} catch (RDFHandlerException rdfe) {
			log.error(rdfe.getMessage());
		} catch (RDFParseException rdfpe) {
			log.error(rdfpe.getMessage());
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		}

		return new LinkedHashModel(collector.getStatements());
	}

	public static Graph deserializeGraph(String graphString, MediaType mediaType) {
		Graph deserializedGraph = null;
		if (mediaType.equals(MediaType.APPLICATION_JSON) || mediaType.getName().equals("application/rdf+json")) {
			deserializedGraph = RDFJSON.rdfJsonToGraph(graphString);
		} else if (mediaType.equals(MediaType.APPLICATION_RDF_XML)) {
			deserializedGraph = deserializeGraph(graphString, new RDFXMLParser());
		} else if (mediaType.equals(MediaType.TEXT_RDF_N3)) {
			deserializedGraph = deserializeGraph(graphString, new N3ParserFactory().getParser());
		} else if (mediaType.getName().equals(RDFFormat.TURTLE.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraph(graphString, new TurtleParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIX.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraph(graphString, new TriXParser());
		} else if (mediaType.getName().equals(RDFFormat.NTRIPLES.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraph(graphString, new NTriplesParser());
		} else if (mediaType.getName().equals(RDFFormat.TRIG.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraph(graphString, new TriGParser());
		} else if (mediaType.getName().equals(RDFFormat.JSONLD.getDefaultMIMEType())) {
			deserializedGraph = deserializeGraph(graphString, new SesameJSONLDParser());
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

}
