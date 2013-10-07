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

package se.kmr.scam.harvesting.fao;

import java.net.URI;
import java.util.List;
import java.util.Set;

import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.repository.Context;
import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.entrystore.repository.impl.converters.ConverterManagerImpl;
import org.openrdf.model.Graph;
import org.quartz.InterruptableJob;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.UnableToInterruptJobException;


/**
 * Harvests metadata objects using iterations over a list we get from FAO.
 * 
 * @author Hannes Ebner
 */
public class FAOHarvestJob implements Job, InterruptableJob{

	private static Log log = LogFactory.getLog(FAOHarvestJob.class); 

	private static boolean interrupted = false; 

	public void execute(JobExecutionContext context) throws JobExecutionException {
		if (interrupted == false ) {
			log.info("FAO Harvester starts: " + ((URI)context.getJobDetail().getJobDataMap().get("contextURI")).toString()); 
			try {
				JobDataMap dataMap = context.getJobDetail().getJobDataMap();
				RepositoryManagerImpl rm = (RepositoryManagerImpl)dataMap.get("rm");

				// These three lines temporary make the current user to admin
				URI realURI = rm.getPrincipalManager().getAuthenticatedUserURI();
				try {
					rm.getPrincipalManager().setAuthenticatedUserURI(rm.getPrincipalManager().getAdminUser().getURI());
					run(context); 
					// sets the current user back to the actually logged in user
				} finally {
					rm.getPrincipalManager().setAuthenticatedUserURI(realURI);
				}		
			} catch (Exception e) {
				log.error(e.getMessage());
				e.printStackTrace(); 
			}
		}
	}

	synchronized public static void run(JobExecutionContext jobContext) throws Exception {
		if (interrupted) {
			return;
		}
		
		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl)dataMap.get("rm");
		ContextManager cm = rm.getContextManager();

		URI contextURI = (URI)dataMap.get("contextURI"); 
		String contextId = contextURI.toString().substring(contextURI.toString().lastIndexOf("/")+1); 
		Context context = cm.getContext(contextId);  	
		String metadataType = dataMap.getString("metadataType");
		
		// Get the resources from FAO
		FAOEngine harvester = new FAOEngine();
		
		List<Integer> resourceIDs = harvester.getResourceList();
		for (Integer id : resourceIDs) {
			FAOMetadata faoMD = harvester.getResource(id);
			createEntry(context, faoMD, metadataType);
			if (interrupted) {
				log.info("FAO Harvester got interrupted");
				break;
			}
		}
		log.info("FAO Harvester done with execution");
	}

	public static void createEntry(Context context, FAOMetadata metadata, String metadataType) throws XPathExpressionException {
		URI entryResourceURI = null;
		URI entryMetadataURI = null;

		try {
			if (metadata.getMetadataURL() != null) {
				entryMetadataURI = URI.create(metadata.getMetadataURL());
			} else {
				log.error("No URI found for FAO metadata, not adding new entry");
				return;
			}
			
			if (metadata.getPdfURI() != null) {
				entryResourceURI = URI.create(metadata.getPdfURI());
			} else if (metadata.getSource() != null) {
				entryResourceURI = URI.create(metadata.getSource());
			} else {
				entryResourceURI = null;
			}
		} catch (java.lang.IllegalArgumentException t) {
			log.error("Bad URI: " + t.getCause().getMessage());
			return;
		}

		Set<Entry> entries = context.getByExternalMdURI(entryMetadataURI); 

		// create entry and set cached metadata only if it doesn't exist yet
		if (entries.isEmpty()) {
			Entry entry = context.createReference(null, entryResourceURI, entryMetadataURI, null);
			setCachedMetadataGraph(entry, metadata, metadataType);
			log.info("Added entry " + entry.getEntryURI() + "; resource URI: " + entryResourceURI + "; metadata URI: " + entryMetadataURI);
		} else {
			
			// update old stuff
			
//			Iterator<Entry> eIt = entries.iterator();
//			if (eIt.hasNext()) {
//				Entry entry = eIt.next();
//				log.info(entry.getEntryURI() + ": setting new resource URI: " + entryResourceURI);
//				entry.setResourceURI(entryResourceURI);
//				log.info(entry.getEntryURI() + ": setting new metadata URI: " + entryMetadataURI);
//				entry.setExternalMetadataURI(entryMetadataURI);
//				log.info(entry.getEntryURI() + ": setting new cached external metadata");
//				setCachedMetadataGraph(entry, metadata, metadataType);
//			}
			
			// update cached metadata?
			
			//log.info("Resource URI " + entryResourceURI + " exists already, skipping creation of entry");
		}
	}

	/**
	 * 
	 * @param entry
	 * @param el
	 * @throws XPathExpressionException
	 */
	private static void setCachedMetadataGraph(Entry entry, FAOMetadata md, String metadataType) throws XPathExpressionException {
		Graph graph = (Graph)ConverterManagerImpl.convert(metadataType, md, entry.getResourceURI(), null); 

		if (graph != null) {
			entry.getCachedExternalMetadata().setGraph(graph);
		} else {
			log.info("Unable to convert metadata");
		}
	}

	public void interrupt() throws UnableToInterruptJobException {
		interrupted = true;
	}

}