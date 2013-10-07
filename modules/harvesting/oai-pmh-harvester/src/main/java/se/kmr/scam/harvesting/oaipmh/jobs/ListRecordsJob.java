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

package se.kmr.scam.harvesting.oaipmh.jobs;

import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.quartz.InterruptableJob;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.UnableToInterruptJobException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import se.kmr.scam.repository.Context;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.LocationType;
import se.kmr.scam.repository.Metadata;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.impl.converters.ConverterManagerImpl;
import ORG.oclc.oai.harvester2.verb.ListRecords;

public class ListRecordsJob implements Job, InterruptableJob {

	private final Log log = LogFactory.getLog(ListRecordsJob.class); 

	// Parse XML Utils
	private static XPathExpression expr;

	private static XPathFactory factory;

	private static XPath xpath;

	private static boolean interrupted = false;
	
	private boolean replaceMetadata = false;
	
	private ValueFactory vf;

	public void execute(JobExecutionContext context) throws JobExecutionException {
		if (interrupted == false ) {
			log.info("ListRecordsJob starts: " + ((URI)context.getJobDetail().getJobDataMap().get("contextURI")).toString() + " metadataType: " + context.getJobDetail().getJobDataMap().getString("metadataType") ); 
			OutputStream out;
			try {
				out = System.out; //new FileOutputStream("/home/eric/test.xml");

				JobDataMap dataMap = context.getJobDetail().getJobDataMap();
				RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");
				PrincipalManager pm = rm.getPrincipalManager();
				vf = rm.getValueFactory();

				//These three lines temporary makes the current user to admin
				URI realURI = pm.getAuthenticatedUserURI();
				try {
					pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
					run(out, context); 
					// this clause sets the current user back to the actually logged in user
				} finally {
					pm.setAuthenticatedUserURI(realURI);
				}		
			} catch (Exception e) {
				log.error(e.getMessage());
				e.printStackTrace(); 
			}
		}
	}

	/**
	 * 
	 * @param out
	 * @throws Exception
	 */
	synchronized public void run(OutputStream out, JobExecutionContext jobContext) throws Exception { 
		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");
		ContextManager cm = rm.getContextManager();
		final PrincipalManager pm = rm.getPrincipalManager();

		initXpath(); 

		URI contextURI = (URI)dataMap.get("contextURI"); 
		String contextId = contextURI.toString().substring(contextURI.toString().lastIndexOf("/")+1); 
		final Context context = cm.getContext(contextId);
		final String metadataType = dataMap.getString("metadataType");
		final String target = dataMap.getString("target");
		String from = dataMap.getString("from");
		String until = dataMap.getString("until");  
		String set = dataMap.getString("set");
		replaceMetadata = "replace".equalsIgnoreCase(rm.getConfiguration().getString(Settings.HARVESTER_OAI_METADATA_POLICY, "skip"));
		boolean fromAutoDetect = "on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.HARVESTER_OAI_FROM_AUTO_DETECT, "on"));
		
		if (from == null && fromAutoDetect) {
			Date latestEntry = null;
			Set<URI> allEntries = context.getEntries();
			for (URI uri : allEntries) {
				Entry entry = context.getByEntryURI(uri);
				if (entry != null && (LocationType.Reference.equals(entry.getLocationType()) || LocationType.LinkReference.equals(entry.getLocationType()))) {
					Date cachedDate = entry.getExternalMetadataCacheDate();
					if (cachedDate != null) {
						if (latestEntry == null || cachedDate.after(latestEntry)) {
							latestEntry = cachedDate;
						}
					}
				}
			}
			if (latestEntry != null) {
				from = new SimpleDateFormat("yyyy-MM-dd").format(latestEntry);
			}
		}
		
		log.info("OAI-PMH metadataType: " + metadataType);
		log.info("OAI-PMH target: " + target);
		log.info("OAI-PMH from: " + from);
		log.info("OAI-PMH until: " + until);
		log.info("OAI-PMH set: " + set);

		// Get the listrecord from the OAI-PMH target
		ListRecords listRecords = null;
		try {
			listRecords = new ListRecords(target, from, until, set, metadataType);
		} catch (UnknownHostException e) {
			// TODO: handle exception write in the RDF tree
			log.info("UnknownHostException since the target is unknown, the havester will be deleted"); 
			jobContext.getScheduler().interrupt(jobContext.getJobDetail().getName(), jobContext.getJobDetail().getGroup()); 
			return ; 
		}
		
		ThreadPoolExecutor exService = null;
		if ("on".equalsIgnoreCase(rm.getConfiguration().getString(Settings.HARVESTER_OAI_MULTITHREADED, "off"))) {
			int cpuCount = Runtime.getRuntime().availableProcessors();
			if (cpuCount == 1) {
				log.info("Multi-threaded harvesting activated, but only one CPU found; continuing single-threaded");
			} else {
				int threadCount = cpuCount + 1;
				log.info("Creating executor for multi-threaded harvesting, using thread pool of " + threadCount + " (available CPUs + 1) threads");
				exService = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadCount);
			}
		} else {
			log.info("Performing single-threaded harvesting");
		}
		
		Date before = new Date();
		int j = 0;
		while (listRecords != null) {
			NodeList errors = listRecords.getErrors();
			if (errors != null && errors.getLength() > 0) {
				log.error("Found errors");
				int length = errors.getLength();
				for (int i = 0; i < length; ++i) {
					Node item = errors.item(i);
					System.out.println(item);
				}
				log.error("Error record: " + listRecords.toString());
				break;
			} 

			//out.write(listRecords.toString().getBytes()); 

			// Get the <Root>-element
			final Element el = listRecords.getDocument().getDocumentElement(); 
			if (el.getElementsByTagName("ListRecords").getLength() == 0) {
				log.error("No ListRecords"); 
				throw new Exception("No ListRecords"); 
			}

			// Get the <ListRecords> element
			Element listRecordsElement = (Element) el.getElementsByTagName("ListRecords").item(0); 
			NodeList recordList = listRecordsElement.getElementsByTagName("record");
			// old NodeList recordList = getRecords(listRecordsElement);
			
			// Create entries from the XML
			for (int i = 0; i < recordList.getLength(); i++) {
				final Element recordElement = (Element) recordList.item(i).cloneNode(true);
				
				if (exService == null) {
					try {
						createEntry(context, recordElement, target, metadataType);
					} catch (XPathExpressionException e) {
						log.error(e.getMessage());
					}
				} else {
					exService.execute(new Runnable() {
						public void run() {
							try {
								pm.setAuthenticatedUserURI(pm.getAdminUser().getURI()); 
								createEntry(context, recordElement, target, metadataType);
							} catch (XPathExpressionException e) {
								log.error(e.getMessage());
							}
						}
					});
					// not sure whether the following is necessary
					while (exService.getQueue().size() > 250) {
						log.info("Waiting before submitting additional Runnables, current queue size is " + exService.getQueue().size());
						Thread.sleep(50);
						log.info("Continuing, the current queue size is " + exService.getQueue().size());
					}
				}
				log.debug("total index: " + j++);
			}

			// Check if there is any resumption token
			String resumptionToken = listRecords.getResumptionToken();
			if (resumptionToken == null || resumptionToken.length() == 0) {
				listRecords = null;
			} else {
				log.info("Got resumption token");
				listRecords = new ListRecords(target, resumptionToken);
			}
		}
		
		if (exService != null) {
			while (exService.getQueue().size() > 0) {
				log.info("Runnables left in queue: " + exService.getQueue().size() + ", waiting");
				Thread.sleep(2000);
			}
			exService.shutdown();
		}
		
		log.info("OAI-PMH harvester done with execution");
		long diff = new Date().getTime() - before.getTime();
		if (j > 0) {
			log.info("Harvesting of " + j + " records took " + diff + " ms (average of " + diff/j + " ms per record)");
		}
	}

	private static void initXpath() {
		factory = XPathFactory.newInstance();
		xpath = factory.newXPath();
		xpath.setNamespaceContext(createNamespace());
	}

	public void createEntry(Context context, Element recordElement, String target, String metadataType) throws XPathExpressionException {
		String identifier = getIdentifier(recordElement); 
		String datestamp = getDatestamp(recordElement);

		if (identifier == null) {
			return;
		}
		
		// workaround for bad installations not using oai_lom
		if ("lom".equalsIgnoreCase(metadataType)) {
			metadataType = "oai_lom";
		}

		// We use OpenRDF URI and Java URI, they do different validity checks,
		// and we need it to work in both.
		// We can create a Reference without a resource URI, but not without a
		// metadata URI, therefore we return only in the case of an invalid
		// metadata URI.
		//
		// Before you touch any of the code down there, think twice!
		
		org.openrdf.model.URI openrdfEntryResourceURI = null;
		try {
			String resourceIdentifier = getResourceIdentifier(recordElement, metadataType);
			if (resourceIdentifier == null) {
				log.warn("Resource identifier is null, skipping resource");
				return;
			}
			openrdfEntryResourceURI =  vf.createURI(resourceIdentifier);
		} catch (IllegalArgumentException e) {
			log.error("Skipping this record, no proper resource URI found: " + e.getMessage());
			return;
		}
		org.openrdf.model.URI openrdfEntryMetadataURI = null;
		try {
			openrdfEntryMetadataURI = vf.createURI(target + "?verb=GetRecord&identifier=" + identifier + "&metadataPrefix=" + metadataType);
		} catch (IllegalArgumentException e) {
			log.error("Illegal external metadata URI, not creating entry: " + e.getMessage());
			return;
		}
		
		URI entryResourceURI = null;
		if (openrdfEntryResourceURI != null) {
			try {
				entryResourceURI = new URI(openrdfEntryResourceURI.toString());
			} catch (URISyntaxException e) {
				log.error(e.getMessage());
			}
		}
		URI entryMetadataURI = null;
		if (openrdfEntryMetadataURI != null) {
			try {
				entryMetadataURI = new URI(openrdfEntryMetadataURI.toString());
			} catch (URISyntaxException e) {
				log.error("Illegal external metadata URI, not creating entry: " + e.getMessage());
				return;
			}
		} else {
			log.error("Illegal external metadata URI, not creating entry");
			return;
		}
		
		Set<Entry> entries = context.getByExternalMdURI(entryMetadataURI);
		
		// if there are no old entries
		if (entries.isEmpty()) {
			Graph g = getExternalMetadataGraphFromXML(recordElement, metadataType, entryResourceURI);
			if (g != null) {
				Entry entry = context.createReference(null, entryResourceURI, entryMetadataURI, null);
				setCachedMetadataGraph(entry, g);
				log.info("Added entry " + entry.getEntryURI() + " with resource " + entryResourceURI + " and metadata " + entryMetadataURI);
			} else {
				log.error("Unable to extract metadata from XML for " + entryMetadataURI);
			}
		} else {
			if (!replaceMetadata) {
				log.info("Cached metadata exists, but harvester is configured to not replace it");
			} else {
				// update old cached external metadata when reharvesting
				Iterator<Entry> eIt = entries.iterator();
				if (eIt.hasNext()) {
					Entry entry = eIt.next();
					if (!entry.getResourceURI().equals(entryResourceURI)) {
						log.info(entry.getEntryURI() + ": setting new resource URI: " + entryResourceURI);
						if (entryResourceURI != null) {
							entry.setResourceURI(entryResourceURI);
						} else {
							log.warn("New resource URI is null, no changes applied");
						}
					}

					URI extMdURI = entry.getExternalMetadataURI();
					if (!extMdURI.equals(entryMetadataURI)) {
						log.warn("New external metadata URI for " + entry.getEntryURI() + " is " + entryMetadataURI);
						if (entryMetadataURI != null) {
							entry.setExternalMetadataURI(entryMetadataURI);
						}
					}
					log.info("Setting new cached external metadata on " + entry.getEntryURI());
					setCachedMetadataGraph(entry, getExternalMetadataGraphFromXML(recordElement, metadataType, entry.getResourceURI()));
				}
			}
			
//			Iterator<Entry> iter = entries.iterator();
//			while(iter.hasNext()) {
//				entry = iter.next(); 
//				String entryURIString = entry.getEntryURI().toString();
//				int indexDateStamp = entryURIString.lastIndexOf("=");
//				String datestampOld = entryURIString.substring(indexDateStamp+1); 
//				if (datestamp.equals(datestampOld)) {
//					// just update modified in the entry since the metadata is the same.
//					entry.getCachedExternalMetadata().setGraph(entry.getCachedExternalMetadata().getGraph());
//				} else {
//					// new metadata must be harvested and cached.
//					// setCachedMetadataGraph(entry, recordElement, metadataType);
//				}
//			}
		}
	}

	private static String getDatestamp(Element el) throws XPathExpressionException {
		initXpath(); 
		expr = xpath.compile("oai:header/oai:datestamp"); 
		return (String) expr.evaluate(el, XPathConstants.STRING);
	}
	
	private Graph getExternalMetadataGraphFromXML(Element el, String metadataType, URI resourceURI) throws XPathExpressionException {
		Node metadata = getMetadataNode(el, metadataType);
		if (metadata == null || metadata.getChildNodes() == null) {
			return null;
		}
		return (Graph) ConverterManagerImpl.convert(metadataType, metadata, resourceURI, null);
	}

	private void setCachedMetadataGraph(Entry entry, Graph graph) {
		if (graph != null) {
			Metadata cachedMD = entry.getCachedExternalMetadata();
			if (cachedMD != null) {
				cachedMD.setGraph(graph);
			}
		}
	}

	private static Node getMetadataNode(Element el, String metadataType) throws XPathExpressionException {
		initXpath(); 
		if (metadataType.equals("oai_dc")) {
			expr = xpath.compile("oai:metadata/oai_dc:dc");
		} else if (metadataType.equals("rdn_dc")) {
			expr = xpath.compile("oai:metadata/rdn_dc:rdndc");
		} else if (metadataType.equals("oai_lom")) {
			expr = xpath.compile("oai:metadata/lom:lom");
		}
		return (Node) expr.evaluate(el, XPathConstants.NODE);
	}
	
	private static NodeList getAboutNodes(Element el, String metadataType) throws XPathExpressionException {
		initXpath(); 
		if (metadataType.equals("oai_dc") || metadataType.equals("rdn_dc")) {
			expr = xpath.compile("oai:about/oai_dc:dc");
			return (NodeList) expr.evaluate(el, XPathConstants.NODESET);
		}
		
		return null;
	}

	private static String getIdentifier(Element el) throws XPathExpressionException {
		initXpath(); 
		expr = xpath.compile("oai:header/oai:identifier"); 
		return (String) expr.evaluate(el, XPathConstants.STRING);
	}

	public static NodeList getRecords(Element el) throws XPathExpressionException {
		expr = xpath.compile("oai:record");  
		return (NodeList) expr.evaluate(el, XPathConstants.NODESET);
	}
	
	private static String getResourceIdentifier(Element el, String metadataType) throws XPathExpressionException {
		initXpath();
		if (metadataType.equals("oai_dc")) {
			expr = xpath.compile("oai:metadata/oai_dc:dc/dc:identifier"); 
		} else if (metadataType.equals("rdn_dc")) {
			expr = xpath.compile("oai:metadata/rdn_dc:rdndc/dc:identifier"); 
		} else if (metadataType.equals("oai_lom")) {
			expr = xpath.compile("oai:metadata/lom:lom/lom:technical/lom:location");
		}
		
		// we only want URIs as identifiers and discard other strings
		NodeList ids = (NodeList) expr.evaluate(el, XPathConstants.NODESET);
		for (int i = 0; i < ids.getLength(); i++) {
			String id = ids.item(i).getTextContent();
			if (id != null) {
				id = id.trim();
				if (id.startsWith("http://") ||
						id.startsWith("https://") ||
						id.startsWith("ftp://")) {
					return id;
				}
			}
		}
		
		return null;
		// return (String) expr.evaluate(el, XPathConstants.STRING);
	}

	private static NamespaceContext createNamespace() {
		// We map the prefixes to URIs
		NamespaceContext ctx = new NamespaceContext() {
			public String getNamespaceURI(String prefix) {
				String uri;
				if (prefix.equals("oai"))
					uri = "http://www.openarchives.org/OAI/2.0/";
				else if (prefix.equals("dc"))
					uri = "http://purl.org/dc/elements/1.1/";
				else if (prefix.equals("dcterms"))
					uri = "http://purl.org/dc/terms/";
				else if (prefix.equals("oai_dc"))
					uri = "http://www.openarchives.org/OAI/2.0/oai_dc/";
				else if (prefix.equals("rdn_dc"))
					uri = "http://www.rdn.ac.uk/oai/rdn_dc/";
				else if (prefix.equals("lom"))
					uri = "http://ltsc.ieee.org/xsd/LOM";
				else 
					uri = null;
				return uri;
			}

			// Dummy implementation - not used!
			public Iterator getPrefixes(String val) {
				return null;
			}

			// Dummy implemenation - not used!
			public String getPrefix(String uri) {
				return null;
			}
		};
		return ctx;
	}

	public void interrupt() throws UnableToInterruptJobException {
		interrupted = true; 
	}

}