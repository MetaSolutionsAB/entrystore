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

package org.entrystore.repository.transformation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.entrystore.GraphType;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.QuotaException;
import org.entrystore.RepositoryException;
import org.entrystore.ResourceType;
import org.entrystore.repository.impl.EntryImpl;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.helpers.StatementCollector;
import org.openrdf.rio.n3.N3ParserFactory;
import org.openrdf.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SCAM2Import {
	
	/** Logger */
	static Logger log = LoggerFactory.getLogger(SCAM2Import.class);

	private Context context;
	private File backup;
	private SailRepository repository;
	private Map<URI, Entry> uri2Entry = new HashMap<URI, Entry>();
	private File files;

	public static final URI manifest;
	public static final URI organizations;
	public static final URI firstChild;
	public static final URI KMRcollection;
	public static final URI KMRURL;
	public static final URI KMRFile;
	public static final URI KMRDisplayName;
	public static final URI KMRrecord;
	public static final URI IMScontent;
	public static final URI IMSItem;
	public static final URI LOMTlocation;
	public static final URI DCTitle;
	public static final URI DCDescription;
	public static final URI DCTTitle;
	public static final URI DCTDescription;
	public static final URI RDF_1;
	
	
	public static final String NSbase = "http://www.imsproject.org/xsd/ims_cp_rootv1p1#";
	public static final String KMRbase = "http://kmr.nada.kth.se/scam";
	public static final String IMSbase = "http://www.imsproject.org/xsd/ims_cp_rootv1p1#";
	public static final String LOMTechnicalbase = "http://ltsc.ieee.org/2002/09/lom-technical#";
	public static final String DCbase = "http://purl.org/dc/elements/1.1/";
	public static final String DCTermsbase = "http://purl.org/dc/terms/";
	
	
	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		manifest = vf.createURI(NSbase + "Manifest");
		organizations = vf.createURI(NSbase + "organizations");
		firstChild = vf.createURI(RDF.NAMESPACE+"_1");
		KMRcollection = vf.createURI(KMRbase +"/datatypes#Collection");
		KMRURL = vf.createURI(KMRbase +"#Url");
		KMRFile = vf.createURI(KMRbase +"#File");
		KMRDisplayName = vf.createURI(KMRbase +"#displayName");
		KMRrecord = vf.createURI(KMRbase +"#record");
		IMScontent = vf.createURI(IMSbase +"content");
		IMSItem = vf.createURI(IMSbase +"Item");
		LOMTlocation = vf.createURI(LOMTechnicalbase+"location");
		DCTitle = vf.createURI(DCbase+"title");
		DCDescription = vf.createURI(DCbase+"description");
		DCTTitle = vf.createURI(DCTermsbase+"title");
		DCTDescription = vf.createURI(DCTermsbase+"description");
		RDF_1 = vf.createURI(RDF.NAMESPACE+"_1");
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
			FileInputStream fileOut = new FileInputStream(new File(this.backup, "manifest.n3"));
			parser.parse(fileOut, "");
			fileOut.close();
			Graph graph = new GraphImpl(collector.getStatements());
			Resource manifestRes = graph.match(null, RDF.TYPE, manifest).next().getSubject();
			Resource organizationsRes = (Resource) graph.match(manifestRes, organizations, null).next().getObject();
			URI first = getFirstChild(graph, organizationsRes);
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

	void recurseFix(Graph graph, URI folder, org.entrystore.List list) {
		for (URI child : getChildren(graph, folder)) {
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
				URI refChild = getContent(graph, child);
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

	void recurse(Graph graph, URI parent, URI folder) {
		handleFolder(graph, parent, folder);
		for (URI child : getChildren(graph, folder)) {
			if (isFolder(graph, child)) {
				recurse(graph, folder, child);
			} else {
				URI refChild = getContent(graph, child);
				if (!isItem(graph, refChild) &&
						!uri2Entry.containsKey(refChild)) {
					handleLeaf(graph, folder, refChild);
				}
			}
		}
	}
	
	void populateFolder(Graph graph, URI parent, URI folder) {
	}
	
	void handleFolder(Graph graph, URI parent, URI folder) {
		Graph closure = getAnonymousClosure(graph, folder);
		java.net.URI parentList = parent == null ? null : uri2Entry.get(parent).getResourceURI();
		Entry folderEntry = context.createResource(null, GraphType.List, ResourceType.InformationResource, parentList);
		handleItem((EntryImpl) folderEntry, closure, parent, folder);
	}

	void handleLeaf(Graph graph, URI parent, URI leaf) {
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

	void handleItem(EntryImpl entry, Graph closure, URI parent, URI item) {
		uri2Entry.put(item, entry);
		Graph metadata = new GraphImpl();
		for (Statement statement : closure) {
			Resource s = entry.getSesameResourceURI();
			Resource subject = statement.getSubject();
			URI predicate = statement.getPredicate();
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
			} else if (subject instanceof URI){ //Works since no other URIs are in subject position in a anonymous closure
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

	void setFile(Data data, Graph closure, URI uri) {
		URI loc = getLocation(closure, uri);
		if (loc != null) {
			String locStr = loc.stringValue();
			locStr = locStr.substring(locStr.indexOf("&uri=")+5);
			locStr = locStr.replace(':', '_');
			locStr = locStr.replace('/', '_');
			try {
				File file = new File(files, locStr);
				if (file.exists()) {
					data.setData(new FileInputStream(file));
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (QuotaException qe) {
				qe.printStackTrace();
			}
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
			if (st.getPredicate().equals(KMRrecord)) {
				continue;
			}
			collect.add(st);
			//If blank
			if (object instanceof Resource 
					&& !(object instanceof URI)) {
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

	URI getContent(Graph graph, URI item) {
		Iterator<Statement> it = graph.match(item, IMScontent, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof URI) {
				return (URI) obj;
			} else {
				return null;
			}
		}
		return null;
	}

	URI getLocation(Graph graph, URI item) {
		Iterator<Statement> it = graph.match(item, LOMTlocation, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof URI) {
				return (URI) obj;
			} else {
				return null;
			}
		}
		return null;	
	}

	URI getFirstChild(Graph graph, Resource subject) {
		Iterator<Statement> it = graph.match(subject, firstChild, null);
		if (it.hasNext()) {
			Value obj = it.next().getObject();
			if (obj instanceof URI) {
				return (URI) obj;
			} else {
				return null;
			}
		}
		return null;
	}
	
	List<URI> getChildren(Graph graph, Resource subject) {
		Vector<URI> children = new Vector<URI>();
		Iterator<Statement> sts = graph.match(subject, null, null);
		while (sts.hasNext()) {
			Statement st = sts.next();
			try {
				String value = st.getPredicate().toString().substring(RDF.NAMESPACE.length());
				int index = Integer.parseInt(value.substring(value.lastIndexOf("_")+1));					
				if (index > children.size()) {
					children.setSize(index); 
				}

				if (st.getObject() instanceof URI) {
					children.set(index-1, (URI) st.getObject());
				}
			} catch (IndexOutOfBoundsException iobe) {
			} catch (NumberFormatException nfe) {
			}
		}
		children.trimToSize();
		return children;
	}
}
