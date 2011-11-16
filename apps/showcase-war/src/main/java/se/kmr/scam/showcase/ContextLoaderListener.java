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

package se.kmr.scam.showcase;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.harvester.Harvester;
import se.kmr.scam.harvester.factory.HarvesterFactoryException;
import se.kmr.scam.harvesting.fao.FAO2RDFGraphConverter;
import se.kmr.scam.harvesting.fao.FAOHarvesterFactory;
import se.kmr.scam.harvesting.oaipmh.harvester.factory.OAIHarvesterFactory;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.backup.BackupFactory;
import se.kmr.scam.repository.backup.BackupScheduler;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.impl.converters.ConverterManagerImpl;
import se.kmr.scam.repository.impl.converters.LOM2RDFConverter;
import se.kmr.scam.repository.impl.converters.OAI_DC2RDFGraphConverter;
import se.kmr.scam.repository.impl.converters.RDF2LOMConverter;
import se.kmr.scam.repository.test.TestSuite;


/**
*
*  Initializes SCAM  
*  
*  @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
public class ContextLoaderListener implements ServletContextListener {
   private static Logger log = LoggerFactory.getLogger(ContextLoaderListener.class);

  public void contextInitialized(ServletContextEvent event) {
	   try { 
		   log.info("Start SCAM Initialize");
		   ServletContext context = event.getServletContext();
		   
		   boolean firstTimeRunningApp = verifyScamRepoMissing();
				   
		   log.info("Initialize RepositoryManager");
		   RepositoryManagerImpl rm = getRepositoryManager();
		   context.setAttribute("RepositoryManager", rm);	
		   
		   log.info("Register converters");
		   registerConverters();
		  
		   
		   log.info("Check if repository should be populated with Default content");
		   String storeType = getStorageType();
		   if (storeType == null || storeType.equals("memory")) {
			   log.info("StoreType=memory, Init Testsuits");
			   TestSuite.initDisneySuite(rm);
			   TestSuite.addEntriesInDisneySuite(rm);
			   TestSuite.HarvesterTestSuite(rm, rm.getPrincipalManager(), rm.getContextManager());
			   TestSuite.initCourseSuite(rm); 
		   } else if (storeType.equals("native") && firstTimeRunningApp) {
			   log.info("First time running Application. Init CourseSuite");
			   TestSuite.initCourseSuite(rm); 
		   } else {
			   log.info("No default content populated");
		   }
		   
		   log.info("Load and start harvesters");
		   ArrayList<Harvester> harvesters = startHarvesters(rm);
		   context.setAttribute("Harvesters", harvesters);
		   
		   log.info("Load and start backup scheduler");
		   BackupScheduler bs =  startBackupScheduler(rm);
		   context.setAttribute("BackupScheduler", bs);
	   } catch (Throwable e) {
		   log.error("ERROR", e);
       }
       
   }
   
   public void contextDestroyed(ServletContextEvent event) {
	   ServletContext context = event.getServletContext();

	   ArrayList<Harvester> harvesters = (ArrayList) context.getAttribute("Harvesters");
	   for (Harvester harvester : harvesters) {
		   harvester.delete();
	   }
	   
	   BackupScheduler backupScheduler = (BackupScheduler) context.getAttribute("BackupScheduler");
	   backupScheduler.delete();
	   
	   RepositoryManagerImpl rm = (RepositoryManagerImpl) context.getAttribute("RepositoryManager");
	   rm.shutdown();
	   
	   log.info("SCAM Context is Destroyed");
   }


   private String getStorageType() {
	   ConfigurationManager confManager = getConfigurationManager();
	   Config config = confManager.getConfiguration();
	   String storeType = config.getString(Settings.SCAM_STORE_TYPE, null);
	   return storeType;
   }

   private boolean verifyScamRepoMissing() {
	   ConfigurationManager confManager = getConfigurationManager();
	   Config config = confManager.getConfiguration();
	   File storeDir = new File(config.getURI(Settings.SCAM_STORE_PATH));
	
	   return !storeDir.exists();
   }

   private ConfigurationManager getConfigurationManager() {
	   ConfigurationManager confManager = null;
	   try {
		   confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
	   } catch (IOException e) {
		   log.error("Unable to load SCAM configuration: " + e.getMessage());
	   }
	   return confManager;
   }
   
   private RepositoryManagerImpl getRepositoryManager() {
	   ConfigurationManager confManager = getConfigurationManager();
	   Config config = confManager.getConfiguration();
		
		// Check the URL in the scam.properties file if you get an error here.
		String domainName = config.getString(Settings.SCAM_BASE_URL, "http://localhost:8080/scam-showcase");
		
		log.info("Create RepositoryManager");
		RepositoryManagerImpl rm = new RepositoryManagerImpl(domainName, confManager.getConfiguration());
		return rm;
		
   }
   
   private void registerConverters() {
	   ConverterManagerImpl.register("oai_dc", new OAI_DC2RDFGraphConverter());
	   ConverterManagerImpl.register("rdn_dc", new OAI_DC2RDFGraphConverter());
	   ConverterManagerImpl.register("fao_xml", new FAO2RDFGraphConverter());
	   ConverterManagerImpl.register("rdf2lom", new RDF2LOMConverter());
	   ConverterManagerImpl.register("lom2rdf", new LOM2RDFConverter());
   }
   
   private BackupScheduler startBackupScheduler(RepositoryManagerImpl rm) {
	    PrincipalManager pm = rm.getPrincipalManager();
	    BackupScheduler bs = null;
		URI userURI = pm.getAuthenticatedUserURI();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			bs = new BackupFactory(rm).getBackupScheduler();
			if (bs != null) {
				bs.run();
			}
		} finally {
			pm.setAuthenticatedUserURI(userURI);
		}
		return bs;
	}

	private ArrayList<Harvester> startHarvesters(RepositoryManagerImpl rm) {
		PrincipalManager pm = rm.getPrincipalManager();
		ContextManager cm = rm.getContextManager();
		URI realURI = pm.getAuthenticatedUserURI();
		ArrayList<Harvester> harvesters = new ArrayList<Harvester>();
		try {
			pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
			Set<URI> entries = cm.getEntries(); 
			java.util.Iterator<URI> iter = entries.iterator(); 
			while (iter.hasNext()) {
				URI entryURI = iter.next();
				Entry entry = cm.getByEntryURI(entryURI); 

				if (entry.getBuiltinType() == BuiltinType.Context) {
					OAIHarvesterFactory fac = new OAIHarvesterFactory();
					if(fac.isOAIHarvester(entry)) {
						try {
							Harvester har = fac.getHarvester(rm, entry.getEntryURI());
							har.run();
							harvesters.add(har);
						} catch (HarvesterFactoryException e) {
							log.error(e.getMessage());
							e.printStackTrace();
						}
					}
					
					FAOHarvesterFactory faoHF = new FAOHarvesterFactory();
					if (faoHF.isFAOHarvester(entry)) {
						try {
							Harvester h = faoHF.getHarvester(rm, entry.getEntryURI());
							h.run();
							harvesters.add(h);
						} catch (HarvesterFactoryException e) {
							log.error(e.getMessage());
							e.printStackTrace();
						}
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(realURI);
		}
		return harvesters;
	}
   
   
   
}