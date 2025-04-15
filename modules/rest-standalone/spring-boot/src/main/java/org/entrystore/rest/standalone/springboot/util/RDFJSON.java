/*
 * Copyright (c) 2007-2025 MetaSolutions AB
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

package org.entrystore.rest.standalone.springboot.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.entrystore.repository.util.NS;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

import static org.eclipse.rdf4j.model.util.Values.iri;

/**
 * A utility class to help converting Sesame Graphs from and to RDF/JSON.
 *
 * @author Hannes Ebner <hebner@csc.kth.se>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RDFJSON {

	private static final Logger log = LoggerFactory.getLogger(RDFJSON.class);

	private static final IRI dtString = iri(NS.xsd, "string");

	private static final IRI dtLangString = iri(NS.rdf, "langString");

	private static final ValueFactory vf = SimpleValueFactory.getInstance();

	/**
	 * Implementation using the json.org API.
	 *
	 * @param json The RDF/JSON string to be parsed and converted into a Sesame
	 *             Graph.
	 * @return A Sesame Graph if successful, otherwise null.
	 */
	public static Model rdfJsonToGraph(JSONObject json) throws RDFParseException {
		Model result = new LinkedHashModel();
		HashMap<String, BNode> id2bnode = new HashMap<>();

		try {
			Iterator<String> subjects = json.keys();
			while (subjects.hasNext()) {
				String subjStr = subjects.next();
				Resource subject;
				try {
					if (subjStr.startsWith("_:")) {
						if (id2bnode.containsKey(subjStr)) {
							subject = id2bnode.get(subjStr);
						} else {
							subject = vf.createBNode();
							id2bnode.put(subjStr, (BNode) subject);
						}
					} else {
						subject = parseAndValidateIRI(subjStr);
					}
				} catch (IllegalArgumentException iae) {
					subject = vf.createBNode();
				}
				JSONObject pObj = json.getJSONObject(subjStr);
				Iterator<String> predicates = pObj.keys();
				while (predicates.hasNext()) {
					String predStr = predicates.next();
					IRI predicate = parseAndValidateIRI(predStr);
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
							if (lang.trim().isEmpty()) {
								lang = null;
							}
						}
						IRI datatype = null;
						if (obj.has("datatype")) {
							datatype = parseAndValidateIRI(obj.getString("datatype"));
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
							object = parseAndValidateIRI(value);
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

	public static Model rdfJsonToGraph(String json) {
		try {
			return rdfJsonToGraph(new JSONObject(json));
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
			return null;
		}
	}

	private static JSONObject getValue(Value v) {
		JSONObject valueObj = new JSONObject();
		if (v instanceof BNode && !v.stringValue().startsWith("_:")) {
			valueObj.put("value", "_:" + v.stringValue());
		} else {
			valueObj.put("value", v.stringValue());
		}
		if (v instanceof Literal l) {
			valueObj.put("type", "literal");
			if (l.getLanguage().isPresent()) {
				valueObj.put("lang", l.getLanguage().get());
			} else if (l.getDatatype() != null) {
				IRI dataType = l.getDatatype();
				// we ignore data types for strings (as introduced by RDF 1.1) to be compatible with RDF 1.0
				if (!dataType.equals(dtString) && !dataType.equals(dtLangString)) {
					valueObj.put("datatype", dataType.stringValue());
				}
			}
		} else if (v instanceof BNode) {
			valueObj.put("type", "bnode");
		} else if (v instanceof IRI) {
			valueObj.put("type", "uri");
		}
		return valueObj;
	}

	public static JSONObject graphToRdfJsonObject(Model graph) {
		try {
			//First build the json structure using maps to avoid iterating through the graph more than once
			HashMap<Resource, HashMap<IRI, JSONArray>> struct = new HashMap<>();
			for (Statement stmt : graph) {
				Resource subject = stmt.getSubject();
				IRI predicate = stmt.getPredicate();
				Value object = stmt.getObject();
				HashMap<IRI, JSONArray> pred2values = struct.get(subject);
				if (pred2values == null) {
					pred2values = new HashMap<>();
					struct.put(subject, pred2values);
					JSONArray values = new JSONArray();
					pred2values.put(predicate, values);
					values.put(getValue(object));
					continue;
				}
				JSONArray values = pred2values.computeIfAbsent(predicate, k -> new JSONArray());
				values.put(getValue(object));
			}

			//Now construct the JSONObject graph from the structure
			JSONObject result = new JSONObject(); //Top level object
			for (Resource subject : struct.keySet()) {
				JSONObject predicateObj = new JSONObject(); //Predicate object where each predicate is a key
				HashMap<IRI, JSONArray> pred2values = struct.get(subject);
				for (IRI predicate : pred2values.keySet()) {
					predicateObj.put(predicate.stringValue(), pred2values.get(predicate)); //The value is an array of objects
				}

				if (subject instanceof BNode && !subject.stringValue().startsWith("_:")) {
					result.put("_:" + subject.stringValue(), predicateObj);
				} else {
					result.put(subject.stringValue(), predicateObj);
				}
			}
			return result;
		} catch (JSONException e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Implementation using the org.json API.
	 *
	 * @param graph A Sesame Graph.
	 * @return An RDF/JSON string if successful, otherwise null.
	 */
	public static String graphToRdfJson(Model graph) {
		JSONObject obj = graphToRdfJsonObject(graph);
		if (obj != null) {
			return obj.toString(2);
		} else {
			return null;
		}
	}

	/**
	 * Implementation using the Streaming API of the Jackson framework.
	 *
	 * @param graph A Sesame Graph.
	 * @return An RDF/JSON string if successful, otherwise null.
	 */
	public static String graphToRdfJsonJackson(Model graph) {
		JsonFactory f = new JsonFactory();
		StringWriter sw = new StringWriter();
		JsonGenerator g;
		try {
			g = f.createGenerator(sw);
			g.useDefaultPrettyPrinter();
		} catch (IOException e) {
			log.error(e.getMessage(), e);
			return null;
		}

		try {
			g.writeStartObject(); // root object
			Set<Resource> subjects = new HashSet<>();
			for (Statement s1 : graph) {
				subjects.add(s1.getSubject());
			}
			for (Resource subject : subjects) {
				if (subject instanceof BNode && !subject.stringValue().startsWith("_:")) {
					g.writeObjectFieldStart("_:" + subject.stringValue()); // subject
				} else {
					g.writeObjectFieldStart(subject.stringValue()); // subject
				}
				Set<IRI> predicates = new HashSet<>();
				for (Statement statement : graph.filter(subject, null, null)) {
					predicates.add(statement.getPredicate());
				}
				for (IRI predicate : predicates) {
					g.writeArrayFieldStart(predicate.stringValue()); // predicate
					for (Statement statement : graph.filter(subject, predicate, null)) {
						Value v = statement.getObject();
						g.writeStartObject(); // value
						if (v instanceof BNode && !v.stringValue().startsWith("_:")) {
							g.writeStringField("value", "_:" + v.stringValue());
						} else {
							g.writeStringField("value", v.stringValue());
						}
						if (v instanceof Literal l) {
							g.writeStringField("type", "literal");
							if (l.getLanguage().isPresent()) {
								g.writeStringField("lang", l.getLanguage().get());
							} else if (l.getDatatype() != null) {
								g.writeStringField("datatype", l.getDatatype().stringValue());
							}
						} else if (v instanceof BNode) {
							g.writeStringField("type", "bnode");
						} else if (v instanceof IRI) {
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
		} catch (IOException e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}

	private static IRI parseAndValidateIRI(String iri) {
		Objects.requireNonNull(iri);

		try {
			URI uri = new URI(iri);

			if (uri.toString().endsWith(".")) {
				String messageTemplate = "Provided string \"%s\" is not a valid URI. Error: %s";
				throw new RDFParseException(String.format(messageTemplate, iri, "String ends in a period '.'"));
			}

			return vf.createIRI(iri);
		} catch (IllegalArgumentException | URISyntaxException ex) {
			String messageTemplate = "Provided string \"%s\" is not a valid URI. Error: %s";
			throw new RDFParseException(String.format(messageTemplate, iri, ex.getMessage()));
		}
	}

}
