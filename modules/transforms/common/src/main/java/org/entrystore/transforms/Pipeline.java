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

package org.entrystore.transforms;

import org.entrystore.Entry;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.converters.Graph2Entries;
import org.entrystore.repository.util.NS;
import org.openrdf.model.Graph;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * @author Matthias Palmér
 * @author Hannes Ebner
 */
public class Pipeline {

	private static Logger log = LoggerFactory.getLogger(Pipeline.class);

	public static final URI transform;
	public static final URI transformPriority;
	public static final URI transformType;
	public static final URI transformArgument;
	public static final URI transformArgumentKey;
	public static final URI transformArgumentValue;

	public static final URI transformDestination;
	public static final URI transformDetectDestination;

	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		transform = vf.createURI(NS.entrystore, "transform");
		transformPriority = vf.createURI(NS.entrystore, "transformPriority");
		transformType = vf.createURI(NS.entrystore, "transformType");
		transformArgument = vf.createURI(NS.entrystore, "transformArgument");
		transformArgumentKey = vf.createURI(NS.entrystore, "transformArgumentKey");
		transformArgumentValue = vf.createURI(NS.entrystore, "transformArgumentValue");
		transformDestination = vf.createURI(NS.entrystore, "transformDestination");
		transformDetectDestination = vf.createURI(NS.entrystore, "transformDetectDestination");		
	}

	private Entry entry;

	private String destination = "";
	private boolean detectDestination = false;
	
	private List<Transform> tsteps = new ArrayList<Transform>();

	private static Map<String, Class<?>> type2Class = null;

	private static Map<String, Class<?>> format2Class = null;

    /**
     *
     * @param entry must be a Pipeline, i.e. the GraphType must be Pipeline.
     */
	public Pipeline(Entry entry) {
		this.entry = entry;
		this.detectTransforms();
	}

    public Entry getEntry() {
        return this.entry;
    }

	private void detectTransforms() {
		Graph graph = ((RDFResource) this.entry.getResource()).getGraph();

        //Fallback, check for transforms in metadata graph instead, old way of doing things.
        if (graph.isEmpty()) {
            graph = this.entry.getMetadataGraph();
        }

		Iterator<Statement> toEntryStmts = graph.match(null, transformDestination, null);
		if (toEntryStmts.hasNext()) {
			destination = toEntryStmts.next().getObject().stringValue();
			if (destination.startsWith("http")) {
				Entry toEntryEntry = this.entry.getContext().getByEntryURI(java.net.URI.create(destination));
				destination = toEntryEntry.getId();
			}
		}

		Iterator<Statement> detectStmts = graph.match(null, transformDetectDestination, null);
		if (detectStmts.hasNext()) {
			detectDestination = detectStmts.next().getObject().stringValue().contains("true");
		}

		Iterator<Statement> stmts = graph.match(null, transform, null);
		while (stmts.hasNext()) {
			Statement statement = (Statement) stmts.next();
			if (statement.getObject() instanceof Resource) {
				Resource transformResource = (Resource) statement.getObject();
				Iterator<Statement> types = graph.match(transformResource, transformType, null);
				if (types.hasNext()) {
					Transform transform = createTransform(types.next().getObject().stringValue());
					if (transform != null) {
						transform.extractArguments(graph, transformResource);
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

	public Set<Entry> run(Entry sourceEntry, java.net.URI listURI) throws TransformException {
		//Get the data
		if (tsteps.size() == 0) {
			throw new IllegalStateException("Pipeline has no recognizable transforms.");
		}
		Transform first = tsteps.get(0);
		Object result = first.transform(this, sourceEntry);
		for (int idx = 1; idx < tsteps.size(); idx++) {
            if (result instanceof Graph) {
                result = tsteps.get(idx).transform(this, (Graph) result);
            } else if (result instanceof Entry) {
                result = tsteps.get(idx).transform(this, (Entry) result);
            } else {
                throw new IllegalStateException("Transform result must be either Graph or Entry.");
            }
		}

        if (result instanceof Graph) {
            Graph graph = (Graph) result;
            Graph2Entries g2e = new Graph2Entries(this.entry.getContext());
            if (detectDestination) {
                return g2e.merge(graph, null, null);
            } else {
                return g2e.merge(graph, destination, listURI);
            }
        } else if (result instanceof Entry) {
            return new HashSet<Entry>(Arrays.asList((Entry) result));
        } else {
            throw new IllegalStateException("Transform result must be either Graph or Entry.");
        }
	}

	private static synchronized void loadTransforms() {
		if (type2Class == null || format2Class == null) {
			type2Class = new HashMap<String, Class<?>>();
			format2Class = new HashMap<String, Class<?>>();
			Reflections reflections = new Reflections(Pipeline.class.getPackage().getName());

			Set<Class<?>> classes = reflections.getTypesAnnotatedWith(TransformParameters.class);
			for (Class c : classes) {
				if (c.isAnnotationPresent(TransformParameters.class)) {
					TransformParameters annot = (TransformParameters) c.getAnnotation(TransformParameters.class);
					type2Class.put(annot.type(), c);
					for (String format : annot.extensions()) {
						format2Class.put(format, c);
					}
				}
			}
		}
	}

}