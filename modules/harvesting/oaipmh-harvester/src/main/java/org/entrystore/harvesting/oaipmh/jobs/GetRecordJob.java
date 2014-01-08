/**
 * Copyright (c) 2007-2010
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

package org.entrystore.harvesting.oaipmh.jobs;

import java.io.IOException;
import java.util.Iterator;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.repository.Context;
import org.quartz.UnableToInterruptJobException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import ORG.oclc.oai.harvester2.verb.GetRecord;

public class GetRecordJob {

	private static Log log = LogFactory.getLog(GetRecordJob.class);

	private static XPathFactory factory;

	private static XPath xpath;

	public static void getRecord(String target, String identifier, String metadataType, Context context) {

		factory = XPathFactory.newInstance();
		xpath = factory.newXPath();
		xpath.setNamespaceContext(createNamespace());
		GetRecord getRecord = null; 
		try {
			getRecord = new GetRecord(target, identifier, metadataType); 
		} catch (TransformerException e) {
			log.error(e.getMessage()); 
		} catch (SAXException e) {
			log.error(e.getMessage()); 
		} catch (ParserConfigurationException e) {
			log.error(e.getMessage()); 
		} catch (IOException e) {
			log.error(e.getMessage()); 
		}
		
		Element el = getRecord.getDocument().getDocumentElement(); 
		if(el.getElementsByTagName("GetRecord").getLength() == 0) {
			log.error("No GetRecord"); 
		}

		// Get the <GetRecord> element
		Element getRecordElement = (Element) el.getElementsByTagName("GetRecord").item(0);
		Element element = (Element) getRecordElement.getElementsByTagName("record").item(0);
		
		try {
			new ListRecordsJob().createEntry(context, element, target, metadataType);
		} catch (XPathExpressionException e) {
			log.error(e.getMessage());
		}
	}

	public void interrupt() throws UnableToInterruptJobException {
		// TODO Auto-generated method stub
		
	}
	
	/**
	 * 
	 * @return
	 */
	private static NamespaceContext createNamespace() {
		// We map the prefixes to URIs
		NamespaceContext ctx = new NamespaceContext() {
			public String getNamespaceURI(String prefix) {
				String uri;
				if (prefix.equals("oai"))
					uri = "http://www.openarchives.org/OAI/2.0/";
				else if (prefix.equals("dc"))
					uri = "http://purl.org/dc/elements/1.1/";
				else if (prefix.equals("oai_dc"))
					uri = "http://www.openarchives.org/OAI/2.0/oai_dc/"; 
				else 
					uri = null;
				return uri;
			}

			// Dummy implementation - not used!
			public Iterator getPrefixes(String val) {
				return null;
			}

			// Dummy implementation - not used!
			public String getPrefix(String uri) {
				return null;
			}
		};
		return ctx;
	}

}
