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

package se.kmr.scam.harvesting.oaipmh.harvester.factory;


import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;

import org.entrystore.repository.Entry;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryProperties;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.impl.RepositoryManagerImpl;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.harvester.Harvester;
import se.kmr.scam.harvester.factory.HarvesterFactory;
import se.kmr.scam.harvester.factory.HarvesterFactoryException;
import se.kmr.scam.harvesting.oaipmh.harvester.OAIHarvester;


public class OAIHarvesterFactory implements HarvesterFactory {
	/** Logger */
	static Logger log = LoggerFactory.getLogger(OAIHarvesterFactory.class);

	public Harvester createHarvester(String target, String metadataType, String set, String timeRegExp, RepositoryManagerImpl rm, URI ownerContextURI) throws HarvesterFactoryException {
		String oai = rm.getConfiguration().getString(Settings.HARVESTER_OAI, "off"); 
		if (oai.equals("off")) {
			throw new HarvesterFactoryException("The OAI-PMH harvester module is not enabled"); 
		}
		
		// Put new RDF in the context graph ; 
		Entry contextEntry = rm.getContextManager().getEntry(ownerContextURI); 
		if (contextEntry == null) {
			throw new HarvesterFactoryException("Can not find the context entry"); 
		}
		
		if (isOAIHarvester(contextEntry)) {
			return getHarvester(rm, ownerContextURI);
		}

		initOAIHarvester(contextEntry, timeRegExp, set, metadataType, target); 
		
		return new OAIHarvester(target, metadataType, set, timeRegExp, rm, ownerContextURI); 
	}

	public Harvester getHarvester(RepositoryManagerImpl rm, URI ownerContextURI) throws HarvesterFactoryException {
		String oai = rm.getConfiguration().getString(Settings.HARVESTER_OAI, "off"); 
		if(oai.equals("off")) {
			throw new HarvesterFactoryException("The OAI-PMH harvester module is not enabled"); 
		}

		String metadataType = null; 
		String target = null;
		String timeRegExp = null; 
		String set = null;

		Entry contextEntry = rm.getContextManager().getEntry(ownerContextURI); 

		if (!isOAIHarvester(contextEntry)) {
			return null; 
		}

		for (Statement s : contextEntry.getGraph()) {
			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"OAIHarvester")) {
				log.info("isHarvester: type OAIHarvester"); 
			}

			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"harvestTimeRegExp")) {
				timeRegExp = s.getObject().stringValue(); 
			}

			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"target")) {
				target = s.getObject().stringValue(); 
			}

			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"metadataType")) {
				metadataType = s.getObject().stringValue(); 
			}
			
			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"set")) {
				set = s.getObject().stringValue(); 
			}
		}

		// TODO: null check..

		return new OAIHarvester(target, metadataType, set, timeRegExp, rm, ownerContextURI);
	}

	public boolean isOAIHarvester(Entry contextEntry) {
		for(Statement s : contextEntry.getGraph()) {
			if(s.getPredicate().toString().equals(RepositoryProperties.NSbase+"harvester")) {
				if(s.getObject().stringValue().equals(RepositoryProperties.NSbase+"OAIHarvester")) {
					return true;
				}
			}
		}
		return false; 
	}

	private void initOAIHarvester(Entry contextEntry, String timeRegExp, String set, String metadataType, String target) {
		Graph graph = contextEntry.getGraph(); 
		ValueFactory vf = graph.getValueFactory(); 
		org.openrdf.model.URI root = vf.createURI(contextEntry.getEntryURI().toString());
		try {
			org.openrdf.model.URI harvesterRoot = vf.createURI(RepositoryProperties.NSbase +"OAIHarvester"); 
			graph.add(root, vf.createURI(RepositoryProperties.NSbase + "harvester") , harvesterRoot);
			graph.add(harvesterRoot, vf.createURI(RepositoryProperties.NSbase +"harvestTimeRegExp"), vf.createLiteral(timeRegExp));
			graph.add(harvesterRoot, vf.createURI(RepositoryProperties.NSbase +"target"), vf.createLiteral(target));
			graph.add(harvesterRoot, vf.createURI(RepositoryProperties.NSbase +"metadataType"), vf.createLiteral(metadataType));
			if (set != null) {
				graph.add(harvesterRoot, vf.createURI(RepositoryProperties.NSbase +"set"), vf.createLiteral(set));
			}
			contextEntry.setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}
	
	public void deleteHarvester(Entry contextEntry) {
		Graph graph = contextEntry.getGraph();
		ValueFactory vf = graph.getValueFactory();
		try {
			org.openrdf.model.URI harvesterRoot = vf.createURI(RepositoryProperties.NSbase + "harvester");
			org.openrdf.model.URI oaiRoot = vf.createURI(RepositoryProperties.NSbase + "OAIHarvester");
			Collection<Statement> statements = new ArrayList<Statement>();
			for (Statement statement : graph) {
				if (statement.getPredicate().equals(harvesterRoot) ||
						statement.getSubject().equals(oaiRoot)) {
					statements.add(statement);
				}
			}
			graph.removeAll(statements);
			contextEntry.setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}

}