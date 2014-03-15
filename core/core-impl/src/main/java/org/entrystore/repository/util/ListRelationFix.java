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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.entrystore.impl.RepositoryProperties;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriterFactory;
import org.openrdf.sail.memory.MemoryStore;


/**
 * This codes converts the old list relation triples to the new ones. This is
 * actually only interesting for a one-time conversion of the Organic.Edunet
 * repository as this is/was the only one which is actively used and was created
 * before the new relationships have been introduced.
 * 
 * Fixes http://jira.iml.umu.se/browse/SCAM-121.
 * 
 * @author Hannes Ebner
 */
public class ListRelationFix {

	public static void main(String[] args) throws RepositoryException, RDFParseException, RDFHandlerException, IOException {
		String BASE = "http://oe.confolio.org/scam/";
		String REPOSITORY_FILE = "/home/hannes/Desktop/test/backup.rdf";
		
		Repository repo = new SailRepository(new MemoryStore());
		repo.initialize();
		RepositoryConnection rc = repo.getConnection();
		ValueFactory vf = rc.getValueFactory();
		TriGParser parser = new TriGParser();
		parser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);
		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		InputStream in = new BufferedInputStream(new FileInputStream(new File(REPOSITORY_FILE)));
		parser.parse(in, BASE);
		
		for (Statement s : collector.getStatements()) {
			org.openrdf.model.URI predicate = s.getPredicate();
			Resource subject = s.getSubject();
			Value object = s.getObject();
			Resource context = s.getContext();
			
			if (RepositoryProperties.referredIn.equals(predicate)) {
				if (object instanceof URI) {
					subject = (URI) s.getObject();
					predicate = RepositoryProperties.hasListMember;
					object = s.getSubject();
					
					String contextStr = context.toString();
					String entryID = contextStr.substring(contextStr.lastIndexOf("/") + 1);
					String contextID = contextStr.substring(BASE.length(), contextStr.length());
					contextID = contextID.substring(0, contextID.indexOf("/"));
					
					context = vf.createURI(URISplit.fabricateURI(BASE, contextID, RepositoryProperties.RELATION, entryID).toString());
					
					//System.out.println("Constructed new statement: " + subject + ", " + predicate + ", " + object + "; " + context);
				} else {
					System.err.println("Something is wrong. Object is not a URI: " + object.stringValue());
				}
			}
			
			if (context != null) {
				rc.add(subject, predicate, object, context);
			} else {
				// Here we fix the lost triples of List resources without a context
				if (RDF.TYPE.equals(predicate) && RDF.SEQ.equals(object)) {
					rc.add(subject, predicate, object, subject);
					System.out.println("Fixed list quadruple: " + subject + ", " + predicate + ", " + object + " [" + subject + "]");
				} else {
					System.out.println("Removed triple without named graph: " + subject + ", " + predicate + ", " + object);
				}
			}
		}
		
		OutputStream out = new BufferedOutputStream(new FileOutputStream(new File(REPOSITORY_FILE + ".converted")));
		RDFWriter writer = new TriGWriterFactory().getWriter(out);
		rc.export(writer);
		rc.close();
		repo.shutDown();
		
		System.out.println("Done");
	}

}