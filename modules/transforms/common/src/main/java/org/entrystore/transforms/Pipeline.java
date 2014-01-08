package org.entrystore.transforms;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.entrystore.repository.impl.converters.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;

import org.entrystore.repository.Entry;
import org.entrystore.repository.impl.converters.Graph2Entries;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pipeline {

	private static Logger log = LoggerFactory.getLogger(Pipeline.class);

	public static final URI transform;
	public static final URI transformToEntry;
	public static final URI transformPriority;
	public static final URI transformType;
	public static final URI transformArgument;

	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		transform = vf.createURI(NS.entrystore, "transform");
		transformToEntry = vf.createURI(NS.entrystore, "transformToEntry");
		transformPriority = vf.createURI(NS.entrystore, "transformPriority");
		transformType = vf.createURI(NS.entrystore, "transformType");
		transformArgument = vf.createURI(NS.entrystore, "transformArgument");
	}

	private Entry entry;

	private String toEntry;

	private List<Transform> tsteps = new ArrayList<Transform>();

	private static Map<String, Class<?>> type2Class = null;

	private static Map<String, Class<?>> format2Class = null;

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
	 * @param typeOrFormat of the transform to use
	 * @return Transform e.g. TabularTransform
	 */
	private Transform createTransform(String typeOrFormat) {
		if (typeOrFormat != null) {
			loadTransforms();

			Class<Transform> transformClass = null;
			transformClass = (Class<Transform>) type2Class.get(typeOrFormat);
			if (transformClass == null) {
				transformClass = (Class<Transform>) format2Class.get(typeOrFormat);
			}

			if (transformClass != null) {
				try {
					return transformClass.newInstance();
				} catch (InstantiationException ie) {
					log.error(ie.getMessage());
				} catch (IllegalAccessException iae) {
					log.error(iae.getMessage());
				}
			}
		}
		return null;
	}

	public boolean run(InputStream data, String mimetype) {
		//Get the data
		Transform first = tsteps.get(0);
		Graph graph = first.transform(data, mimetype);
		for (int idx = 0; idx < tsteps.size(); idx++) {
			graph = tsteps.get(idx).transform(graph);
		}

		Graph2Entries g2e = new Graph2Entries(this.entry.getContext());
		g2e.merge(graph, toEntry);

		return true;
	}

	private static synchronized void loadTransforms() {
		if (type2Class == null || format2Class == null) {
			type2Class = new HashMap<String, Class<?>>();
			format2Class = new HashMap<String, Class<?>>();

			Reflections reflections = new Reflections("org.entrystore.transforms");
			Set<Class<?>> classes = reflections.getTypesAnnotatedWith(TransformParameters.class);
			for (Class c : classes) {
				if (c.isAnnotationPresent(TransformParameters.class)) {
					TransformParameters annot = (TransformParameters) c.getAnnotation(TransformParameters.class);
					type2Class.put(annot.type(), c);
					for (String format : annot.formats()) {
						format2Class.put(format, c);
					}
				}
			}
		}
	}

}