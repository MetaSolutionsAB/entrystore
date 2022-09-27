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

package org.entrystore.repository.transformation;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Graph;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.GraphImpl;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.impl.ValueFactoryImpl;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.rio.n3.N3ParserFactory;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.QuotaException;
import org.entrystore.ResourceType;
import org.entrystore.impl.EntryImpl;
import org.entrystore.repository.RepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;


public class SCAM2Import {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(SCAM2Import.class);

	private Context context;
	private File backup;
	private SailRepository repository;
	private Map<IRI, Entry> uri2Entry = new HashMap<IRI, Entry>();
	private File files;

	public static final IRI manifest;
	public static final IRI organizations;
	public static final IRI firstChild;
	public static final IRI KMRcollection;
	public static final IRI KMRURL;
	public static final IRI KMRFile;
	public static final IRI KMRDisplayName;
	public static final IRI KMRrecord;
	public static final IRI IMScontent;
	public static final IRI IMSItem;
	public static final IRI LOMTlocation;
	public static final IRI DCTitle;
	public static final IRI DCDescription;
	public static final IRI DCTTitle;
	public static final IRI DCTDescription;
	public static final IRI RDF_1;
	
	
	public static final String NSbase = "http://www.imsproject.org/xsd/ims_cp_rootv1p1#";
	public static final String KMRbase = "http://kmr.nada.kth.se/scam";
	public static final String IMSbase = "http://www.imsproject.org/xsd/ims_cp_rootv1p1#";
	public static final String LOMTechnicalbase = "http://ltsc.ieee.org/2002/09/lom-technical#";
	public static final String DCbase = "http://purl.org/dc/elements/1.1/";
	public static final String DCTermsbase = "http://purl.org/dc/terms/";
	
	
	static {
		ValueFactory vf = SimpleValueFactory.getInstance();
		manifest = vf.createIRI(NSbase + "Manifest");
		organizations = vf.createIRI(NSbase + "organizations");
		firstChild = vf.createIRI(RDF.NAMESPACE+"_1");
		KMRcollection = vf.createIRI(KMRbase +"/datatypes#Collection");
		KMRURL = vf.createIRI(KMRbase +"#Url");
		KMRFile = vf.createIRI(KMRbase +"#File");
		KMRDisplayName = vf.createIRI(KMRbase +"#displayName");
		KMRrecord = vf.createIRI(KMRbase +"#record");
		IMScontent = vf.createIRI(IMSbase +"content");
		IMSItem = vf.createIRI(IMSbase +"Item");
		LOMTlocation = vf.createIRI(LOMTechnicalbase+"location");
		DCTitle = vf.createIRI(DCbase+"title");
		DCDescription = vf.createIRI(DCbase+"description");
		DCTTitle = vf.createIRI(DCTermsbase+"title");
		DCTDescription = vf.createIRI(DCTermsbase+"description");
		RDF_1 = vf.createIRI(RDF.NAMESPACE+"_1");
	}

	public SCAM2Import(Context importToContext, String pathToScam2BackupDir) {
		this.context = importToContext;
		this.backup = new File(pathToScam2BackupDir);
		this.files = new File(this.backup, "files");
		this.repository = new SailRepository(new MemoryStore());
	}
	
	public void doImport() {
		RDFParser parser = new N3ParserFactory().getParser();
		parser.setDatatypeHandling(RDFParser.DatatypeHandling.IGNORE);

		StatementCollector collector = new StatementCollector();
		parser.setRDFHandler(collector);
		try {
			InputStream fileOut = Files.newInputStream(new File(this.backup, "manifest.n3").toPath());
			parser.parse(fileOut, "");
			fileOut.close();
			Graph graph = new GraphImpl(collector.getStatements());
			Resource manifestRes = graph.match(null, RDF.TYPE, manifest).next().getSubject();
			Resource organizationsRes = (Resource) graph.match(manifestRes, organizations, null).next().getObject();
			IRI first = getFirstChild(graph, organizationsRes);
			recurse(graph, null, first);
			Entry top = context.get("_top");
			Entry firstEntry = uri2Entry.get(first);
			((org.entrystore.List) top.getResource()).addChild(firstEntry.getEntryURI());
			recurseFix(graph, first, (org.entrystore.List) firstEntry.getResource());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (RDFHandlerException e) {
			e.printStackTrace();
		} catch (RDFParseException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void recurseFix(Graph graph, IRI folder, org.entrystore.List list) {
		for (IRI child : getChildren(graph, folder)) {
			Entry entryChild = uri2Entry.get(child);
			if (entryChild != null) {
				try {
					list.addChild(entryChild.getEntryURI());
				} catch(RepositoryException re) {
				}
				if (entryChild.getGraphType() == GraphType.List) {
					recurseFix(graph, child, (org.entrystore.List) entryChild.getResource());
				}
			} else {
				IRI refChild = getContent(graph, child);
				Entry refEntryChild = uri2Entry.get(refChild);
				if (refEntryChild != null) {
					try {
						list.addChild(refEntryChild.getEntryURI());						
					} catch(RepositoryException re) {
					}
				}
			}
		}
	}

	void recurse(Graph graph, IRI parent, IRI folder) {
		handleFolder(graph, parent, folder);
		for (IRI child : getChildren(graph, folder)) {
			if (isFolder(graph, child)) {
				recurse(graph, folder, child);
			} else {
				IRI refChild = getContent(graph, child);
				if (!isItem(graph, refChild) &&
						!uri2Entry.containsKey(refChild)) {
					handleLeaf(graph, folder, refChild);
				}
			}
		}
	}
	
	void populateFolder(Graph graph, IRI parent, IRI folder) {
	}
	
	void handleFolder(Graph graph, IRI parent, IRI folder) {
		Graph closure = getAnonymousClosure(graph, folder);
		java.net.URI parentList = parent == null ? null : uri2Entry.get(parent).getResourceURI();
		Entry folderEntry = context.createResource(null, GraphType.List, ResourceType.InformationResource, parentList);
		handleItem((EntryImpl) folderEntry, closure, parent, folder);
	}

	void handleLeaf(Graph graph, IRI parent, IRI leaf) {
		log.warn("Working with \""+leaf.stringValue()+"\"");
		Graph closure = getAnonymousClosure(graph, leaf);
		java.net.URI parentList = parent == null ? null : uri2Entry.get(parent).getResourceURI();
		Entry leafEntry = null;
		if(isURL(closure, leaf)) {
			leafEntry = context.createLink(null, java.net.URI.create(leaf.stringValue()), parentList);
		} else if (isFile(closure, leaf)) {
			leafEntry = context.createResource(null, GraphType.None, ResourceType.InformationResource, parentList);
			setFile((Data) leafEntry.getResource(), closure, leaf);
		} else {
			log.warn("Entry, \""+leaf.stringValue()+"\", is neither URL or uploaded file.");
		}
		if (leafEntry != null) {
			handleItem((EntryImpl) leafEntry, closure, parent, leaf);			
		}
	}

	void handleItem(EntryImpl entry, Graph closure, IRI parent, IRI item) {
		uri2Entry.put(item, entry);
		Graph metadata = new GraphImpl();
		for (Statement statement : closure) {
			Resource s = entry.getSesameResourceURI();
			Resource subject = statement.getSubject();
			IRI predicate = statement.getPredicate();
			String object = statement.getObject().stringValue();
			if (object.startsWith("urn:x-")) {
				continue;
			}
			if (predicate.equals(RDF.TYPE)) {
				if (!object.startsWith(KMRbase) 
						&& !object.startsWith(IMSbase)) {
					metadata.add(s, RDF.TYPE, statement.getObject());
				}
			} else if (predicate.stringValue().equals(DCbase+"format")) {
				entry.setMimetype(object);
			} else if (predicate.stringValue().equals(DCTermsbase+"extent")) {
				entry.setFileSize(Long.parseLong(object));
			} else if (predicate.equals(LOMTlocation)) {
				//Taken care of separately.
			} else if (predicate.equals(KMRDisplayName)) {
				entry.setFilename(statement.getObject().stringValue());
			} else if (subject instanceof IRI){ //Works since no other URIs are in subject position in a anonymous closure
				if (!predicate.stringValue().startsWith(RDF.NAMESPACE+"_")) { //Ignore all rdf-collection relations.
					if (predicate.equals(DCTitle)) {
						metadata.add(s, DCTTitle, statement.getObject());						
					} else if (predicate.equals(DCDescription)) {
						ValueFactory vf = ValueFactoryImpl.getInstance();
						BNode bnode = vf.createBNode();
						metadata.add(s, DCTDescription, bnode);
						metadata.add(bnode, RDF.VALUE, statement.getObject());						
					} else {
						metadata.add(s, predicate, statement.getObject());
					}
				}
			} else {
				metadata.add(statement);
			}
		}
		//Do stuff with record. created data + ACL.
		entry.getLocalMetadata().setGraph(metadata);
	}

	void setFile(Data data, Graph closure, IRI uri) {
		IRI loc = getLocation(closure, uri);
		if (loc != null) {
			String locStr = loc.stringValue();
			locStr = locStr.substring(locStr.indexOf("&uri=")+5);
			locStr = locStr.replace(':', '_');
			locStr = locStr.replace('/', '_');
			try {
				File file = new File(files, locStr);
				if (file.exists()) {
					data.setData(Files.newInputStream(file.toPath()));
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (QuotaException qe) {
				qe.printStackTrace();
			}
		}
	}
	
	Graph getAnonymousClosure(Graph graph, IRI subject) {
		Graph collect = new GraphImpl();
		collectAnonymousClosure(graph, subject, collect);
		return collect;
	}

	
	void collectAnonymousClosure(Graph graph, Resource subject, Graph collect) {
		Iterator<Statement> it = graph.match(subject, null, null);
		while(it.hasNext()) {
			Statement st = it.next();
			Value object = st.getObject();
			if (st.getPredicate().equals(KMRrecord)) {
				continue;
			}
			collect.add(st);
			//If blank
			if (object instanceof Resource 
					&& !(object instanceof IRI)) {
				collectAnonymousClosure(graph, (Resource) object, collect);
			}
		}
	}
	
	boolean isFolder(Graph graph, Resource subject) {
		return graph.match(subject, RDF_1, null).hasNext();
		//return graph.match(subject, RDF.TYPE, KMRcollection).hasNext();
	}

	boolean isItem(Graph graph, Resource subject) {
		return graph.match(subject, RDF.TYPE, IMSItem).hasNext();
	}

	boolean isURL(Graph graph, Resource subject) {
		return graph.match(subject, RDF.TYPE, KMRURL).hasNext();
	}

	boolean isFile(Graph graph, Resource subject) {
		return graph.match(subject, RDF.TYPE, KMRFile).hasNext();
	}

	IRI getContent(Graph graph, IRI item) {
		Iterator<Statement> it = graph.match(item, IMScontent, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof IRI) {
				return (IRI) obj;
			} else {
				return null;
			}
		}
		return null;
	}

	IRI getLocation(Graph graph, IRI item) {
		Iterator<Statement> it = graph.match(item, LOMTlocation, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof IRI) {
				return (IRI) obj;
			} else {
				return null;
			}
		}
		return null;	
	}

	IRI getFirstChild(Graph graph, Resource subject) {
		Iterator<Statement> it = graph.match(subject, firstChild, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof IRI) {
				return (IRI) obj;
			} else {
				return null;
			}
		}
		return null;
	}
	
	List<IRI> getChildren(Graph graph, Resource subject) {
		Vector<IRI> children = new Vector<IRI>();
		Iterator<Statement> sts = graph.match(subject, null, null);
		while (sts.hasNext()) {
			Statement st = sts.next();
			try {
				String value = st.getPredicate().toString().substring(RDF.NAMESPACE.length());
				int index = Integer.parseInt(value.substring(value.lastIndexOf("_")+1));					
				if (index > children.size()) {
					children.setSize(index); 
				}

				if (st.getObject() instanceof IRI) {
					children.set(index-1, (IRI) st.getObject());
				}
			} catch (IndexOutOfBoundsException iobe) {
			} catch (NumberFormatException nfe) {
			}
		}
		children.trimToSize();
		return children;
	}
}
