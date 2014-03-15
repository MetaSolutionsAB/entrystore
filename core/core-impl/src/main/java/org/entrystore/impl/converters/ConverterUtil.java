/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.entrystore.repository.util.NS;
import org.ieee.ltsc.lom.LOM;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

/**
 * @author Hannes Ebner
 */
public class ConverterUtil {
	
	private static Logger log = LoggerFactory.getLogger(ConverterUtil.class);
	
	private static String VCARD4J_CONFIG = "vcard4j-cfg.xml";
	
	private static String LOM10 = "lom10.xsd";
	
	private static boolean lomProcessingPrepared = false;
	
	protected static synchronized void prepareLOMProcessing() {
		if (lomProcessingPrepared) {
			return;
		}
		
		// Set LOM 1.0 XML schema location
				
		URL lomURL = findResource(LOM10);
		if (lomURL != null) {
			log.info("Setting " + LOM.SCHEMA_LOCATION_PROPERTY + " to " + lomURL);
			System.setProperty(LOM.SCHEMA_LOCATION_PROPERTY, lomURL.toString());
		} else {
			log.warn("Unable to find " + LOM10 + " in classpath, using default instead");
		}
		
		// Set VCARD4J XML config

		URL vcardURL = findResource(VCARD4J_CONFIG);
		if (vcardURL != null) {
			log.info("Setting vcard4j.configuration to " + vcardURL.toString());
			System.setProperty("vcard4j.configuration", vcardURL.toString());
		} else {
			log.warn("Unable to find " + VCARD4J_CONFIG + " in classpath");	
		}
		
		lomProcessingPrepared = true;
	}
	
	public static URL findResource(String res) {
		URL resURL = Thread.currentThread().getContextClassLoader().getResource(res);
				
		if (resURL == null) {
			String classPath = System.getProperty("java.class.path");
			String[] pathElements = classPath.split(System.getProperty("path.separator"));
			for (String element : pathElements)	{
				File newFile = new File(element, res);
				if (newFile.exists()) {
					try {
						resURL = newFile.toURL();
					} catch (MalformedURLException e) {
						log.error(e.getMessage());
					}
				}
			}
		}
		
		return resURL;
	}
	
	public static LOMImpl readLOMfromReader(Reader reader) {
		prepareLOMProcessing();
		LOMImpl lom = null;
		JAXBContext jaxbContext;
		try {
			jaxbContext = JAXBContext.newInstance("org.ieee.ltsc.lom.jaxb.lomxml");
			final Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			lom = (LOMImpl) unmarshaller.unmarshal(reader);
		} catch (JAXBException e) {
			log.error(e.getMessage());
		}
		return lom;
	}
	
	public static LOMImpl readLOMfromReader(Node n) {
		if (n == null) {
			return null;
		}
		prepareLOMProcessing();
		LOMImpl lom = null;
		JAXBContext jaxbContext;
		try {
			jaxbContext = JAXBContext.newInstance("org.ieee.ltsc.lom.jaxb.lomxml");
			final Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			lom = (LOMImpl) unmarshaller.unmarshal(n);
		} catch (JAXBException e) {
			log.error(e.getMessage());
		} catch (NullPointerException npe) {
			// sometimes an NPE occurs at org.ieee.ltsc.datatype.impl.Util.removeInvalidXMLCharacters(Util.java:25)
			log.error(npe.getMessage());
		}
		return lom;
	}
	
	public static void writeLOMtoWriter(LOM lom, Writer writer) {
		prepareLOMProcessing();
		JAXBContext jaxbContext;
		try {
			jaxbContext = JAXBContext.newInstance("org.ieee.ltsc.lom.jaxb.lomxml");
			final Marshaller marshaller = jaxbContext.createMarshaller();
			marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, new Boolean(true));
			marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
			marshaller.marshal(lom, writer);
		} catch (JAXBException e) {
			log.error(e.getMessage());
		}
	}
	
	/**
	 * @param graph
	 *            The Graph to be serialized.
	 * @param writer
	 *            One of the following: N3Writer, NTriplesWriter,
	 *            RDFXMLPrettyWriter, RDFXMLWriter, TriGWriter, TriXWriter,
	 *            TurtleWriter
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

	public static boolean isValidated(Graph graph, URI resURI) {
		if (graph == null || resURI == null) {
			return false;
		}
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI resourceURI = vf.createURI(resURI.toString());
		Date latestValidationDate = null;
		boolean result = false;
		
		Iterator<Statement> annotations = graph.match(resourceURI, vf.createURI(NS.lom, "annotation"), null);
		while (annotations.hasNext()) {
			Value resource = annotations.next().getObject();
			if (resource instanceof Resource) {
				Iterator<Statement> validationStmnts = graph.match((Resource) resource, vf.createURI(NS.oe, "validationStatus"), null);
				if (validationStmnts.hasNext()) {
					Value validationValue = validationStmnts.next().getObject();
					if (validationValue instanceof org.openrdf.model.URI) { 

						// 8.2 Date

						Iterator<Statement> dateStmnts = graph.match((Resource) resource, vf.createURI(NS.dcterms, "date"), null);
						if (dateStmnts.hasNext()) {
							Statement dateStmnt = dateStmnts.next();
							Value date = dateStmnt.getObject();
							if (date instanceof Literal) {
								Literal dateLiteral = (Literal) date;						
								if (vf.createURI(NS.dcterms, "W3CDTF").equals(dateLiteral.getDatatype())) {
									GregorianCalendar dateValue = null;
									try {
										dateValue = dateLiteral.calendarValue().toGregorianCalendar();
									} catch (IllegalArgumentException iae) {
										Date parsedDate = RDF2LOMConverter.parseDateFromString(dateLiteral.stringValue());
										if (parsedDate != null) {
											dateValue = new GregorianCalendar();
											dateValue.setTime(parsedDate);
										} else {
											log.warn("Unable to parse 8.2 Date of " + resourceURI + ": " + iae.getMessage());
										}
									}
									if (dateValue != null) {
										if (latestValidationDate != null && dateValue.getTime().before(latestValidationDate)) {
											continue;
										} else {
											latestValidationDate = dateValue.getTime();
										}
									}
								}
							}
						}

						// ValidationStatus
						
						org.openrdf.model.URI acceptedURI = vf.createURI(NS.lrevoc, "Accepted");
						org.openrdf.model.URI rejectedURI = vf.createURI(NS.lrevoc, "Rejected");
						if (validationValue.equals(acceptedURI)) {
							result = true;
						} else if (validationValue.equals(rejectedURI)) {
							result = false;
						}
					}
				}
			}
		}
		
		return result;
	}
	
	/**
	 * @param serializedGraph
	 *            The Graph to be deserialized.
	 * @param parser
	 *            Instance of the following: N3Parser, NTriplesParser,
	 *            RDFXMLParser, TriGParser, TriXParser, TurtleParser
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
		
		return new GraphImpl(collector.getStatements());
	}
	
	public static String convertGraphToLOM(Graph graph, org.openrdf.model.URI resourceURI) {
		prepareLOMProcessing();
		RDF2LOMConverter converter = new OERDF2LOMConverter();
		converter.setLRESupport(true);
		LOMImpl lom = new LOMImpl();
		converter.convertAll(graph, lom, resourceURI, null);
		StringWriter writer = new StringWriter();
		writeLOMtoWriter(lom, writer);
		return writer.toString();
	}
	
	public static Graph convertLOMtoGraph(String lomString, URI resourceURI) {
		prepareLOMProcessing();
		StringReader reader = new StringReader(lomString);
		LOMImpl lom = readLOMfromReader(reader);
		LOM2RDFConverter converter = new OELOM2RDFConverter();
		converter.setLRESupport(true);
		Graph graph = new GraphImpl();
		converter.convertAll(lom, graph, new URIImpl(resourceURI.toString()), null);
		return graph;
	}
	
	public static Graph convertLOMtoGraph(LOMImpl lom, URI resourceURI) {
		LOM2RDFConverter converter = new OELOM2RDFConverter();
		converter.setLRESupport(true);
		Graph graph = new GraphImpl();
		converter.convertAll(lom, graph, new URIImpl(resourceURI.toString()), null);
		return graph;
	}

}