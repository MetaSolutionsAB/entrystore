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

package se.kmr.scam.repository.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.trig.TriGParser;
import org.openrdf.rio.trig.TriGWriterFactory;
import org.openrdf.sail.memory.MemoryStore;

import se.kmr.scam.repository.RepositoryProperties;

public class RecoverNG {
	
	
	SailRepository repo;

	private URL base;

	public RecoverNG() {
		repo = new SailRepository(new MemoryStore());
		try {
			repo.initialize();
		} catch (RepositoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void convert(File in, URL base) {
		this.base = base;
		TriGParser parser = new TriGParser();
		parser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);
		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		try {
			parser.parse(new BufferedInputStream(new FileInputStream(in)), "");
			repo.getConnection().add(collector.getStatements());
		} catch (RDFParseException e) {
			e.printStackTrace();
		} catch (RDFHandlerException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
		
		convert();
	}
	
	protected void convert() {
		try {
			ValueFactory vf = repo.getValueFactory();
			RepositoryConnection co = repo.getConnection();
			Set<URI> entries = new HashSet<URI>();
			RepositoryResult<Statement>  stmts = co.getStatements(null, RDF.TYPE, null, false);
			while(stmts.hasNext()) {
				Statement statement = stmts.next();
				if (statement.getObject() instanceof URI 
						&& statement.getObject().equals(RepositoryProperties.LinkReference)) {
					entries.add((URI) statement.getSubject());
				}
			}

			HashMap<URI,URI> entry2res = new HashMap<URI, URI>();
			stmts = co.getStatements(null, RepositoryProperties.resource, null, false);
			while (stmts.hasNext()) {
				Statement st = stmts.next();
				if (entries.contains(st.getSubject())) {
					entry2res.put((URI) st.getSubject(), (URI) st.getObject());
				}
			}

			//Default graph
			GraphImpl def = new GraphImpl(co.getStatements(null, null, null, false, (Resource) null).asList());		
			for(URI entry: entries) {
				URISplit split = new URISplit(entry, base);
				URI mdURI = vf.createURI(split.getMetadataURI().toString());
				//Missing triple in entryinformation (assuming it does not hur to add the triple if it already there.
				co.add(entry, RepositoryProperties.metadata, mdURI, entry);
				Graph subgraph = getAnonymousClosure(def, entry2res.get(entry));
				//Add subgraph as metadata NG.
				co.add(subgraph, mdURI);
			}
			co.remove(def, (Resource) null);
		} catch (RepositoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void save(File out) {
		try {
			BufferedOutputStream buf = new BufferedOutputStream(new FileOutputStream(out));
			RDFWriter writer = new TriGWriterFactory().getWriter(buf);
			repo.getConnection().export(writer);
		} catch (IOException ioe) {
			ioe.printStackTrace();
		} catch (RDFHandlerException rdfhe) {
			rdfhe.printStackTrace();
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}

	Graph getAnonymousClosure(Graph graph, URI subject) {
		Graph collect = new GraphImpl();
		collectAnonymousClosure(graph, subject, collect);
		return collect;
	}

	
	void collectAnonymousClosure(Graph graph, Resource subject, Graph collect) {
		Iterator<Statement> it = graph.match(subject, null, null);
		while(it.hasNext()) {
			Statement st = it.next();
			Value object = st.getObject();
			collect.add(st);
			//If blank
			if (object instanceof Resource 
					&& !(object instanceof URI)) {
				collectAnonymousClosure(graph, (Resource) object, collect);
			}
		}
	}

	public static void main(String[] args) {
		RecoverNG rng = new RecoverNG();
		try {
			rng.convert(new File("/home/hannes/Desktop/merged.rdf"), new URL("http://oe.confolio.org/scam/"));
			rng.save(new File("/home/hannes/Desktop/result.rdf"));
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}
}
