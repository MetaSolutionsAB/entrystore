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

package org.entrystore.transforms;

import org.entrystore.Entry;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Matthias Palmér
 * @author Hannes Ebner
 */
public abstract class Transform implements Comparable<Transform> {

	private int prio = 0;

	private Map<String, String> arguments = new HashMap<String, String>();

	public void extractArguments(Graph graph, Resource resource) {
		Iterator<Statement> prios = graph.match(null, Pipeline.transformPriority, null);
		if (prios.hasNext()) {
			Value obj = prios.next().getObject();
			if (obj instanceof Literal) {
				this.prio = ((Literal) obj).integerValue().intValue();
			}
		}

		Iterator<Statement> args = graph.match(resource, Pipeline.transformArgument, null);
		while (args.hasNext()) {
			Statement s = args.next();
			if (s.getObject() instanceof BNode) {
				String keyStr = null;
				String valueStr = null;
				Iterator<Statement> argKeyIt = graph.match((BNode) s.getObject(), Pipeline.transformArgumentKey, null);
				if (argKeyIt.hasNext()) {
					Value argKey = argKeyIt.next().getObject();
					if (argKey instanceof Literal) {
						keyStr = ((Literal) argKey).stringValue();
						Iterator<Statement> argValueIt = graph.match((BNode) s.getObject(), Pipeline.transformArgumentValue, null);
						if (argValueIt.hasNext()) {
							Value argValue = argValueIt.next().getObject();
							if (argValue instanceof Literal) {
								valueStr = ((Literal) argValue).stringValue();
							}
						}
					}
				}
				if (keyStr != null && valueStr != null) {
					arguments.put(keyStr.toLowerCase(), valueStr);
				}
			}
		}
	}

	public int compareTo(Transform step) {
		return this.prio - step.prio;
	}

	public int getPrio() {
		return prio;
	}

	public void setPrio(int prio) {
		this.prio = prio;
	}

	public abstract Graph transform(Entry sourceEntry, InputStream data, String mimetype) throws TransformException;

	public Graph transform(Graph graph) throws TransformException {
		return graph;
	}

	public Map<String, String> getArguments() {
		return this.arguments;
	}

}