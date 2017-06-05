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

package org.entrystore.harvesting.oaipmh.target.crosswalk;

import java.io.StringWriter;
import java.util.Properties;

import org.entrystore.Entry;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.rdfxml.util.RDFXMLPrettyWriterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ORG.oclc.oai.server.crosswalk.Crosswalk;
import ORG.oclc.oai.server.verb.CannotDisseminateFormatException;

/**
 * Converts an entry and its metadata graphs to LOM/DC.
 * 
 * @author Hannes Ebner
 */
public class Crosswalk2OaiLomDc extends Crosswalk {

	private static final Logger log = LoggerFactory.getLogger(Crosswalk2OaiLomDc.class);
	

	/**
	 * The constructor assigns the schemaLocation associated with this
	 * crosswalk.
	 * 
	 * @param properties
	 *            properties that are needed to configure the crosswalk.
	 */
	public Crosswalk2OaiLomDc(Properties properties) {
		super("http://www.openarchives.org/OAI/2.0/oai_dc/ http://www.openarchives.org/OAI/2.0/oai_dc.xsd");
	}

	/**
	 * Can this nativeItem be represented in DC format?
	 * 
	 * @param nativeItem
	 *            a record in native format
	 * @return true if DC format is possible, false otherwise.
	 */
	public boolean isAvailableFor(Object nativeItem) {
		return nativeItem instanceof Entry;
	}

	/**
	 * Perform the actual crosswalk.
	 * 
	 * @param nativeItem
	 *            the native "item". present in the <metadata> element.
	 * @return a String containing the XML to be stored within the <metadata>
	 *         element.
	 * @exception CannotDisseminateFormatException
	 *                nativeItem doesn't support this format.
	 */
	public String createMetadata(Object nativeItem) throws CannotDisseminateFormatException {
		log.debug("Called createMetadata()");
		
		Entry entry = null;
		if (nativeItem instanceof Entry) {
			entry = (Entry) nativeItem;
		} else {
			throw new IllegalArgumentException("Argument must be an entry"); 
		}
		
		String result = "";
		
		if (entry != null) {
			Graph mergedMetadata = entry.getMetadataGraph();
			result = convertGraphToRDFXML(mergedMetadata);
		}

		return result;
	}
	
	private String convertGraphToRDFXML(Graph g) {
		StringWriter sw = new StringWriter();
		RDFWriter prettyWriter = new RDFXMLPrettyWriterFactory().getWriter(sw);
		try {
			prettyWriter.handleNamespace("dc", NS.dc);
			prettyWriter.handleNamespace("dcterms", NS.dcterms);
			prettyWriter.handleNamespace("foaf", NS.foaf);
			prettyWriter.handleNamespace("es", NS.entrystore);
			prettyWriter.startRDF();
			for (Statement statement : g) {
				prettyWriter.handleStatement(statement);
			}
			prettyWriter.endRDF();
		} catch (RDFHandlerException e) {
			log.error("Unable to serialize entry to RDF/XML", e);
		}
		
		// this hack is pretty bad, but so far the only solution to get
		// rid of the XML encoding header which we don't want to have here
		StringBuilder sb = new StringBuilder(sw.toString());
		sb.delete(0, sb.indexOf("\n"));
		
		return sb.toString();
	}

}
