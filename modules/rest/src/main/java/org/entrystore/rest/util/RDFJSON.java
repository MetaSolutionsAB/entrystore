package org.entrystore.rest.util;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class to help converting Sesame Graphs from and to RDF/JSON.
 * 
 * @author Hannes Ebner <hebner@csc.kth.se>
 */
public class RDFJSON {
	
	private static Logger log = LoggerFactory.getLogger(RDFJSON.class);

	/**
	 * Implementation using the json.org API.
	 * 
	 * @param json
	 *            The RDF/JSON string to be parsed and converted into a Sesame
	 *            Graph.
	 * @return A Sesame Graph if successful, otherwise null.
	 */
	public static Graph rdfJsonToGraph(JSONObject json) {
		Graph result = new GraphImpl();
		HashMap<String, BNode> id2bnode = new HashMap<String, BNode>();
		ValueFactory vf = result.getValueFactory();

		try {
			JSONObject input = json;
			Iterator<String> subjects = input.keys();
			while (subjects.hasNext()) {
				String subjStr = subjects.next();
				Resource subject = null;
				try {
					if (subjStr.startsWith("_:")) {
						if (id2bnode.containsKey(subjStr)) {
							subject = id2bnode.get(subjStr);
						} else {
							subject = vf.createBNode();
							id2bnode.put(subjStr, (BNode) subject);
						}
					} else {
						subject = vf.createURI(subjStr);
					}
				} catch (IllegalArgumentException iae) {
					subject = vf.createBNode();
				}
				JSONObject pObj = input.getJSONObject(subjStr);
				Iterator<String> predicates = pObj.keys();
				while (predicates.hasNext()) {
					String predStr = predicates.next();
					URI predicate = vf.createURI(predStr);
					JSONArray predArr = pObj.getJSONArray(predStr);
					for (int i = 0; i < predArr.length(); i++) {
						Value object = null;
						JSONObject obj = predArr.getJSONObject(i);
						if (!obj.has("value")) {
							continue;
						}
						String value = obj.getString("value");
						if (!obj.has("type")) {
							continue;
						}
						String type = obj.getString("type");
						String lang = null;
						if (obj.has("lang")) {
							lang = obj.getString("lang");
						}
						URI datatype = null;
						if (obj.has("datatype")) {
							datatype = vf.createURI(obj.getString("datatype"));
						}
						if ("literal".equals(type)) {
							if (lang != null) {
								object = vf.createLiteral(value, lang);
							} else if (datatype != null) {
								object = vf.createLiteral(value, datatype);
							} else {
								object = vf.createLiteral(value);
							}
						} else if ("bnode".equals(type)) {
							if (id2bnode.containsKey(value)) {
								object = id2bnode.get(value);
							} else {
								object = vf.createBNode();
								id2bnode.put(value, (BNode) object);
							}
						} else if ("uri".equals(type)) {
							object = vf.createURI(value);
						}
						result.add(subject, predicate, object);
					}
				}
			}
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
			return null;
		}

		return result;
	}
	
	public static Graph rdfJsonToGraph(String json) {
		try {
			return rdfJsonToGraph(new JSONObject(json));
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}
	
	/**
	 * Implementation using the org.json API.
	 * 
	 * @param graph
	 *            A Sesame Graph.
	 * @return An RDF/JSON string if successful, otherwise null.
	 */
	public static String graphToRdfJson(Graph graph) {
		JSONObject result = new JSONObject();
		try {
			Set<Resource> subjects = new HashSet<Resource>();
			for (Statement s1 : graph) {
				subjects.add(s1.getSubject());
			}
			for (Resource subject : subjects) {
				JSONObject predicateObj = new JSONObject();
				Set<URI> predicates = new HashSet<URI>();
				Iterator<Statement> s2 = graph.match(subject, null, null);
				while (s2.hasNext()) {
					predicates.add(s2.next().getPredicate());
				}
				for (URI predicate : predicates) {
					JSONArray valueArray = new JSONArray();
					Iterator<Statement> stmnts = graph.match(subject, predicate, null);
					while (stmnts.hasNext()) {
						Value v = stmnts.next().getObject();
						JSONObject valueObj = new JSONObject();
						if (v instanceof BNode && !v.stringValue().startsWith("_:")) {
							valueObj.put("value", "_:"+v.stringValue());
						} else {
							valueObj.put("value", v.stringValue());							
						}
						if (v instanceof Literal) {
							valueObj.put("type", "literal");	
							Literal l = (Literal) v;
							if (l.getLanguage() != null) {
								valueObj.put("lang", l.getLanguage());
							} else if (l.getDatatype() != null) {
								valueObj.put("datatype", l.getDatatype().stringValue());
							}
						} else if (v instanceof BNode) {
							valueObj.put("type", "bnode");
						} else if (v instanceof URI) {
							valueObj.put("type", "uri");
						}
						valueArray.put(valueObj);
					}
					predicateObj.put(predicate.stringValue(), valueArray);
				}
				if (subject instanceof BNode && !subject.stringValue().startsWith("_:")) {
					result.put("_:"+subject.stringValue(), predicateObj);					
				} else {
					result.put(subject.stringValue(), predicateObj);					
				}
			}
			return result.toString(2);
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}
	
	public static JSONObject graphToRdfJsonObject(Graph graph) {
		try {
			return new JSONObject(graphToRdfJson(graph));
		} catch (JSONException e) {
			log.error(e.getMessage());
			return null;
		}
	}
	
	/**
	 * Implementation using the Streaming API of the Jackson framework.
	 * 
	 * @param graph
	 *            A Sesame Graph.
	 * @return An RDF/JSON string if successful, otherwise null.
	 */
	public static String graphToRdfJsonJackson(Graph graph) {
		JsonFactory f = new JsonFactory();
		StringWriter sw = new StringWriter();
		JsonGenerator g = null;
		try {
			g = f.createJsonGenerator(sw);
			g.useDefaultPrettyPrinter();
		} catch (IOException e) {
			log.error(e.getMessage(), e);
			return null;
		}
		
		try {
			g.writeStartObject(); // root object
			Set<Resource> subjects = new HashSet<Resource>();
			for (Statement s1 : graph) {
				subjects.add(s1.getSubject());
			}
			for (Resource subject : subjects) {
				if (subject instanceof BNode && !subject.stringValue().startsWith("_:")) {
					g.writeObjectFieldStart("_:"+subject.stringValue()); // subject
				} else {
					g.writeObjectFieldStart(subject.stringValue()); // subject					
				}
				Set<URI> predicates = new HashSet<URI>();
				Iterator<Statement> s2 = graph.match(subject, null, null);
				while (s2.hasNext()) {
					predicates.add(s2.next().getPredicate());
				}
				for (URI predicate : predicates) {
					g.writeArrayFieldStart(predicate.stringValue()); // predicate
					Iterator<Statement> stmnts = graph.match(subject, predicate, null);
					while (stmnts.hasNext()) {
						Value v = stmnts.next().getObject();
						g.writeStartObject(); // value
						if (v instanceof BNode && ! v.stringValue().startsWith("_:")) {
							g.writeStringField("value", "_:"+v.stringValue());							
						} else {
							g.writeStringField("value", v.stringValue());							
						}
						if (v instanceof Literal) {
							g.writeStringField("type", "literal");
							Literal l = (Literal) v;
							if (l.getLanguage() != null) {
								g.writeStringField("lang", l.getLanguage());
							} else if (l.getDatatype() != null) {
								g.writeStringField("datatype", l.getDatatype().stringValue());
							}
						} else if (v instanceof BNode) {
							g.writeStringField("type", "bnode");
						} else if (v instanceof URI) {
							g.writeStringField("type", "uri");
						}
						g.writeEndObject(); // value
					}
					g.writeEndArray(); // predicate
				} 
				g.writeEndObject(); // subject
			}
			g.writeEndObject(); // root object
			g.close();
			return sw.toString();
		} catch (JsonGenerationException e) {
			log.error(e.getMessage(), e);
		} catch (IOException ioe) {
			log.error(ioe.getMessage(), ioe);
		}
		return null;
	}

}
