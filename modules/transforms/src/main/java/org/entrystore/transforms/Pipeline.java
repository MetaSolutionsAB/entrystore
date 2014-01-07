package org.entrystore.transforms;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;

import org.entrystore.repository.Entry;
import org.entrystore.repository.impl.converters.Graph2Entries;

public class Pipeline {
	
	public static final String NSbase = "http://entrystore.org/terms/";
	public static final URI transform;
	public static final URI transformToEntry;
	public static final URI transformPriority;
	public static final URI transformType;
	public static final URI transformArgument;
	
	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		transform = vf.createURI(NSbase + "transform");
		transformToEntry = vf.createURI(NSbase + "transformToEntry");
		transformPriority = vf.createURI(NSbase + "transformPriority");
		transformType = vf.createURI(NSbase + "transformType");
		transformArgument = vf.createURI(NSbase + "transformArgument");
	}
	
	private Entry entry;
	private String toEntry;
	private ArrayList<Transform> tsteps = new ArrayList<Transform>();
	
	public Pipeline(Entry entry) {
		this.entry = entry;
		this.detectTransforms();
	}
	private void detectTransforms() {
		Graph md = this.entry.getMetadataGraph();

		Iterator<Statement> toEntryStmts = md.match(null, transformToEntry, null);
		if (toEntryStmts.hasNext()) {
			toEntry = toEntryStmts.next().getObject().stringValue();
		}
		
		Iterator<Statement> stmts = md.match(null, transform, null);
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			if (statement.getObject() instanceof Resource) {
				Resource transformResource = (Resource) statement.getObject();
				Iterator<Statement> types = md.match(transformResource, transformType, null);
				if (types.hasNext()) {
					Transform transform = createTransform(types.next().getObject().stringValue());
					if (transform != null) {
						transform.extractArguments(md, transformResource);
						tsteps.add(transform);
					}
				}
			}
		}
		Collections.sort(tsteps);
	}
	
	/**
	 * Factory method.
	 * 
	 * @param type of the transform to use.
	 * @return Transform e.g. TarqlTransform
	 */
	private Transform createTransform(String type) {
		if (type == "tarql") {
			return new TarqlTransform();
		}
		return null;
	}
	public boolean run(InputStream data, String mimetype) {
		//Get the data
		Transform first = tsteps.get(0);
		Graph graph = first.transform(data, mimetype);
		for (int idx=0;idx<tsteps.size();idx++) {
			graph = tsteps.get(idx).transform(graph);
		}
		
		Graph2Entries g2e = new Graph2Entries(this.entry.getContext());
		g2e.merge(graph, toEntry);

		return true;
	}
}