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

package org.entrystore.repository.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.entrystore.repository.GraphType;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.impl.converters.OERDF2LOMConverter;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.URIImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class helps with analyzing the usage of metadata of a repository or
 * certain contexts.
 * 
 * Initially written to get statistics of the usage of the Organic.Edunet AP.
 * 
 * @author Hannes Ebner
 */
public class MetadataStatistics {
	
	private static Logger log = LoggerFactory.getLogger(MetadataStatistics.class);
	
	private PrincipalManager pm;
	
	private ContextManager cm;
	
	public MetadataStatistics(RepositoryManager rm) {
		this.pm = rm.getPrincipalManager();
		this.cm = rm.getContextManager();
	}
	
	public Map<org.openrdf.model.URI, Integer> getFirstLevelPredicates(URI resourceURI, Graph metadata) {
		if (resourceURI == null || metadata == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}
		
		Map<org.openrdf.model.URI, Integer> result = new HashMap<org.openrdf.model.URI, Integer>();
		Iterator<Statement> firstLevel = metadata.match(new URIImpl(resourceURI.toString()), null, null);
		while (firstLevel.hasNext()) {
			org.openrdf.model.URI predicate = firstLevel.next().getPredicate();
			int count = 0;
			if (result.containsKey(predicate)) {
				count = result.get(predicate);
			}
			result.put(predicate, count++);
		}
		
		return result;
	}
	
	public String getContextStatistics(String contextURI) {
		String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/")+1);
		Context context = cm.getContext(contextId);
		log.info("Context has URI: " + context.getURI());
		if (context == null) {
			log.warn("Context not found: " + contextURI);
			return null;
		}
		
		Set<URI> entries = context.getEntries();
		
		log.info("Found " + entries.size() + " for " + contextId);
		
		StringBuilder sb = new StringBuilder();
		for (URI entryURI : entries) {
			String entryStats = getEntryStatistics(context, entryURI);
			if (entryStats != null) {
				if (sb.length() > 0) {
					sb.append("\r\n");
				}
				sb.append(entryStats);
			}
		}
		
		return sb.toString();
	}
	
	public void writeContextStatistics(Writer writer, String contextURI) {
		String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/")+1);
		Context context = cm.getContext(contextId);
		log.info("Context has URI: " + context.getURI());
		if (context == null) {
			log.warn("Context not found: " + contextURI);
			return;
		}
		
		Set<URI> entries = context.getEntries();
		
		log.info("Found " + entries.size() + " for " + contextId);

		try {
			for (URI entryURI : entries) {
				String entryStats = getEntryStatistics(context, entryURI);
				if (entryStats != null) {
					writer.append("\r\n");
					writer.append(entryStats);
				}
			}
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		}
	}
	
	public String getEntryStatistics(Context context, URI entryURI) {
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		
		String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/")+1);
		
		Entry entry = context.get(entryId);
		if (entry == null) {
			log.warn("Entry not found: " + entryURI);
			return null;
		}
		log.info("Entry found: " + entry.getEntryURI());
		
		if (!entry.getGraphType().equals(GraphType.None)) {
			log.info("Found ResourceType. Skipping.");
			return null;
		}
		
		Graph mergedMd = new GraphImpl();
		if (entry.getLocalMetadata() != null) {
			log.info("Local metadata found");
			mergedMd.addAll(entry.getLocalMetadata().getGraph());
		}
		if (entry.getCachedExternalMetadata() != null) {
			log.info("Cached metadata found");
			mergedMd.addAll(entry.getCachedExternalMetadata().getGraph());
		}
		
		LOMImpl lom = new LOMImpl();
		
		ValueFactory vf = mergedMd.getValueFactory();
		org.openrdf.model.URI resURI = null;
		if (entry.getResourceURI() != null) {
			resURI = vf.createURI(entry.getResourceURI().toString());
		}
		org.openrdf.model.URI mdURI = null;
		if (entry.getLocalMetadataURI() != null) {
			mdURI = vf.createURI(entry.getLocalMetadataURI().toString());
		}
		
		OERDF2LOMConverter converter = new OERDF2LOMConverter();
		converter.convertAll(mergedMd, lom, resURI, mdURI);
		
//		if (!converter.hasAllMandatoryElements()) {
//			return null;
//		}
		
		pm.setAuthenticatedUserURI(currentUserURI);
		
		return converter.statistics.toString();
	}
	
	private String getHeaders() {
		OERDF2LOMConverter converter = new OERDF2LOMConverter();
		return converter.statistics.getHeaders();
	}
	
	public void writeStatsToFile(String path, Map<String, String> contexts) {
		//writeStatsOfAllToFile(path, contexts);
		for (String contextURI : contexts.keySet()) {
			String fileName = contexts.get(contextURI) + ".csv";
			writeStatsOfContextToFile(path, fileName, contextURI);
		}
	}
	
	public void writeStatsOfAllToFile(String path, Map<String, String> contexts) {
		Writer writer;
		try {
			writer = new BufferedWriter(new FileWriter(new File(path, "all.csv")));
		} catch (IOException e) {
			log.error(e.getMessage());
			return;
		}
		
		try {
			writer.write(getHeaders());
			writer.write("\r\n");
			for (String contextURI : contexts.keySet()) {
				log.info("Getting statistics for context " + contextURI);
				writer.write(getContextStatistics(contextURI));
				writer.write("\r\n");
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		} finally {
			try {
				writer.flush();
				writer.close();
			} catch (IOException ignored) {}
		}
	}
	
	public void writeStatsOfContextToFile(String path, String file, String contextURI) {
		Writer writer;
		try {
			writer = new BufferedWriter(new FileWriter(new File(path, file)));
		} catch (IOException e) {
			log.error(e.getMessage());
			return;
		}
		
		try {
			writer.write(getHeaders());
			writer.write("\r\n");
			log.info("Getting statistics for context " + contextURI);
			//writer.write(getContextStatistics(contextURI));
			writeContextStatistics(writer, contextURI);
			writer.write("\r\n");
		} catch (IOException e) {
			log.error(e.getMessage());
		} finally {
			try {
				writer.flush();
				writer.close();
			} catch (IOException ignored) {}
		}
	}
	
	public void run() {
		Map<String, String> contexts = new HashMap<String, String>();
		contexts.put("http://knowone.csc.kth.se/scam/8", "aua");
		contexts.put("http://knowone.csc.kth.se/scam/7", "bmukk");
		contexts.put("http://knowone.csc.kth.se/scam/6", "grnet");
		contexts.put("http://knowone.csc.kth.se/scam/5", "kth");
//		contexts.put("http://knowone.csc.kth.se/scam/2", "ariadne");
//		contexts.put("http://knowone.csc.kth.se/scam/9", "ariadne-big");
//		contexts.put("http://oe.confolio.org/scam/4", "fao_capacity");
//		contexts.put("http://oe.confolio.org/scam/96", "fao_document");
//		contexts.put("http://oe.confolio.org/scam/5", "intute");
//		contexts.put("http://oe.confolio.org/scam/55", "aua");
//		contexts.put("http://oe.confolio.org/scam/30", "bce");
//		contexts.put("http://oe.confolio.org/scam/36", "bmukk-bmlfuw");
//		contexts.put("http://oe.confolio.org/scam/33", "ea");
//		contexts.put("http://oe.confolio.org/scam/32", "euls");
//		contexts.put("http://oe.confolio.org/scam/31", "mogert");
//		contexts.put("http://oe.confolio.org/scam/29", "uah");
//		contexts.put("http://oe.confolio.org/scam/34", "umb");
//		contexts.put("http://oe.confolio.org/scam/49", "usamvb-fa");
//		contexts.put("http://oe.confolio.org/scam/57", "miksike");
//		contexts.put("http://oe.confolio.org/scam/95", "orgeprints");
//		contexts.put("http://oe.confolio.org/scam/81", "nova-agroasis");
//		contexts.put("http://oe.confolio.org/scam/82", "noan");
		
		writeStatsToFile("/home/hannes/Desktop/stats/", contexts);
	}

}