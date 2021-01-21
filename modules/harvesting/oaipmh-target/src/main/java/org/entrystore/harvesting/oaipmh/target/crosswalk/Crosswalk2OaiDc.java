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

import ORG.oclc.oai.server.crosswalk.Crosswalk;
import ORG.oclc.oai.server.verb.CannotDisseminateFormatException;
import org.apache.commons.lang.StringEscapeUtils;
import org.entrystore.Entry;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Properties;

/**
 * Converts SCAM Entry and its metadata graphs to oai_dc.
 * 
 * @author Hannes Ebner
 * @author Eric Johansson
 * @version $Revision$
 */
public class Crosswalk2OaiDc extends Crosswalk {

	private static final Logger log = LoggerFactory.getLogger(Crosswalk2OaiDc.class);

	/**
	 * The constructor assigns the schemaLocation associated with this
	 * crosswalk.
	 * 
	 * @param properties
	 *            properties that are needed to configure the crosswalk.
	 */
	public Crosswalk2OaiDc(Properties properties) {
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
			// Graph mergedMetadata = entry.getMetadataGraph();
			// result = convertGraphToRDFXML(mergedMetadata);
			
			result = convertMetadataToDCSimple(entry);
		}

		return result;
	}
	
	private String convertMetadataToDCSimple(Entry e) {
		StringBuffer result = new StringBuffer();
		result.append("\n<oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/oai_dc/ http://www.openarchives.org/OAI/2.0/oai_dc.xsd\">\n");
		
		result.append("<dc:identifier>");
		result.append(e.getResourceURI().toString());
		result.append("</dc:identifier>\n");

		URI creator = e.getCreator();
		if (creator != null) {
			result.append("<dc:creator>");
			result.append(creator.toString());
			result.append("</dc:creator>\n");
		}
		
		for (URI contributor : e.getContributors()) {
			if (contributor.toString().endsWith("_admin")) {
				continue;
			}
			result.append("<dc:contributor>");
			result.append(contributor.toString());
			result.append("</dc:contributor>\n");
		}
		
		Graph md = e.getMetadataGraph();
		ValueFactory vf = md.getValueFactory();
		org.openrdf.model.URI resURI = vf.createURI(e.getResourceURI().toString());
		
		// dc:title
		
		Iterator<Statement> stmnts = md.match(resURI, vf.createURI(NS.dc, "title"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:title", (Literal) o));
			}
		}
		stmnts = md.match(resURI, vf.createURI(NS.dcterms, "title"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:title", (Literal) o));
			}
		}
		
		// dc:description
		
		stmnts = md.match(resURI, vf.createURI(NS.dc, "description"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:description", (Literal) o));
			}
		}
		stmnts = md.match(resURI, vf.createURI(NS.dcterms, "description"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Resource) {
				Iterator<Statement> descStmnts = md.match((Resource) o, RDF.VALUE, null);
				while (descStmnts.hasNext()) {
					Value descO = descStmnts.next().getObject();
					if (descO instanceof Literal) {
						result.append(constructLine("dc:description", (Literal) descO));
					}
				}
			}
		}
		
		// dc:coverage
		
		stmnts = md.match(resURI, vf.createURI(NS.dcterms, "coverage"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Resource) {
				Iterator<Statement> coverageStmnts = md.match((Resource) o, RDF.VALUE, null);
				while (coverageStmnts.hasNext()) {
					Value covO = coverageStmnts.next().getObject();
					if (covO instanceof Literal) {
						result.append(constructLine("dc:coverage", (Literal) covO));
					}
				}
			}
		}
		
		// dc:format
		
		String mimeType = e.getMimetype();
		if (mimeType != null) {
			result.append("<dc:format>");
			result.append(mimeType);
			result.append("</dc:format>\n");
		}
		
		// dc:language
		
		stmnts = md.match(resURI, vf.createURI(NS.dc, "language"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:language", (Literal) o));
			}
		}
		stmnts = md.match(resURI, vf.createURI(NS.dcterms, "language"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Resource) {
				Iterator<Statement> langStmnts = md.match((Resource) o, RDF.VALUE, null);
				while (langStmnts.hasNext()) {
					Value langO = langStmnts.next().getObject();
					if (langO instanceof Literal) {
						result.append(constructLine("dc:language", (Literal) langO));
					}
				}
			}
		}
			
		// dc:date
		
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		String modified = sdf.format(e.getModifiedDate());
		result.append("<dc:date xsi:type=\"http://purl.org/dc/terms/W3CDTF\">");
		result.append(modified);
		result.append("</dc:date>\n");
		
		// dc:rights
		
		stmnts = md.match(resURI, vf.createURI(NS.dc, "rights"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:rights", (Literal) o));
			}
		}
		stmnts = md.match(resURI, vf.createURI(NS.dcterms, "rights"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Resource) {
				Iterator<Statement> rightsStmnts = md.match((Resource) o, RDF.VALUE, null);
				while (rightsStmnts.hasNext()) {
					Value rightsO = rightsStmnts.next().getObject();
					if (rightsO instanceof Literal) {
						result.append(constructLine("dc:rights", (Literal) rightsO));
					}
				}
			}
		}
		
		// dc:subject
		
		stmnts = md.match(resURI, vf.createURI(NS.dc, "subject"), null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			if (o instanceof Literal) {
				result.append(constructLine("dc:subject", (Literal) o));
			}
		}

		// <dc:type>
		
		stmnts = md.match(resURI, RDF.TYPE, null);
		while (stmnts.hasNext()) {
			Value o = stmnts.next().getObject();
			result.append("<dc:type>");
			result.append(StringEscapeUtils.escapeXml(o.stringValue()));
			result.append("</dc:type>\n");
		}
		
		// TODO <dc:publisher>
		
		result.append("</oai_dc:dc>");
		return result.toString();
	}
	
	public String constructLine(String property, Literal lit) {
		StringBuffer sb = new StringBuffer();
		
		String lang = lit.getLanguage();
		sb.append("<");
		sb.append(property);
		if (lang != null) {
			sb.append(" xml:lang=\"");
			sb.append(lang);
			sb.append("\"");
		}
		sb.append(">");
		sb.append(StringEscapeUtils.escapeXml(lit.stringValue()));
		sb.append("</");
		sb.append(property);
		sb.append(">\n");
		
		return sb.toString();
	}

}