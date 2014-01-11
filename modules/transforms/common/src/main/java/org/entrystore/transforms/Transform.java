package org.entrystore.transforms;

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

		Iterator<Statement> args = graph.match(null, Pipeline.transformArgument, null);
		while (args.hasNext()) {
			Statement s = args.next();
			if (s.getObject() instanceof BNode) {
				String keyStr = null;
				String valueStr = null;
				Iterator<Statement> argKeyIt = graph.match((BNode) s.getObject(), Pipeline.transformArgumentKey, null);
				if (args.hasNext()) {
					Statement argKey = args.next();
					if (argKey instanceof Literal) {
						keyStr = ((Literal) argKey).stringValue();
						Iterator<Statement> argValueIt = graph.match((BNode) s.getObject(), Pipeline.transformArgumentValue, null);
						if (args.hasNext()) {
							Statement argValue = args.next();
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

	public abstract Graph transform(InputStream data, String mimetype) throws TransformException;

	public Graph transform(Graph graph) throws TransformException {
		return graph;
	}

	public Map<String, String> getArguments() {
		return this.arguments;
	}

}