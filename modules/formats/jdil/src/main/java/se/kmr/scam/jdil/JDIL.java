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

package se.kmr.scam.jdil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

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
import org.openrdf.model.impl.URIImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Matthias Palmer , Eric Johansson
 *
 */
public class JDIL {

	private Namespaces namespaces;
	static Logger log = LoggerFactory.getLogger(JDIL.class);

	public JDIL(Namespaces namespaces) {
		this.namespaces = namespaces;
	}

	HashMap<String, String> globalNamespaces;

	public String getAbbr(String uri) {
		return namespaces.abbreviate(uri); 
	}
	
	/**
	 * Converts a JDIL-JSON-object to a RDF graph. 
	 * Example JDIL-JSON: 
	 * {"metadata": {"dcterms:description":"nice data"}} 
	 * @param jsonObject the JSONobject with the JDIL-JSON
	 * @return a new Graph
	 */
	public Graph importJDILtoGraph(JSONObject jsonObject) {
		try {
			Graph graph = new org.openrdf.model.impl.GraphImpl();
			JDILParser.removeJDILStar(jsonObject);
			this.JDILtoGraph(jsonObject, graph, null, new HashMap<String, BNode>());
			return graph;
		} catch (JSONException e) {
			e.printStackTrace();
		} 
		return null; 
	}

	private void JDILtoGraph(JSONObject jsonObject, Graph graph, Resource subject, Map<String, BNode> bnodeResolver) throws JSONException {
		if (subject == null) { //Assume there is a @id.
			subject = graph.getValueFactory().createURI(namespaces.expand(jsonObject.getString("@id")));
		}

		Iterator keyIt = jsonObject.keys();
		while(keyIt.hasNext()) {
			String key = keyIt.next().toString();
			if (key.startsWith("@")) {
				//One of the builtin that should be ignored, e.g. @id or @namespaces
				continue;
			}

			org.openrdf.model.URI spred = graph.getValueFactory().createURI(namespaces.expand(key));
			Object obj= jsonObject.opt(key);

			// Recurse (base pattern)
			if (obj instanceof JSONObject) {
				//single property
				extract(graph, subject, spred, obj, bnodeResolver);
			} else if (obj instanceof JSONArray) {
				//Repeated properties as array
				JSONArray array = (JSONArray) obj; 
				for (int i = 0; i < array.length(); i++) {
					obj = array.get(i);
					extract(graph, subject, spred, obj, bnodeResolver);
				}
			} else {
				//Take care of literal.
				graph.add(subject, spred, graph.getValueFactory().createLiteral(obj.toString()));
			}
		}
	}

	private void extract(Graph graph, Resource subject, org.openrdf.model.URI spred, Object obj, Map<String, BNode> bnodeResolver) throws JSONException {
		if (((JSONObject) obj).has("@id") && !((JSONObject) obj).has("@isBlank")) {
			//Take care of object
			org.openrdf.model.URI sobj = graph.getValueFactory().createURI(namespaces.expand(((JSONObject)obj).getString("@id")));
			graph.add(subject, spred, sobj);
			JDILtoGraph((JSONObject)obj, graph, sobj, bnodeResolver);
		} else {
			if (((JSONObject) obj).has("@isBlank")) {
				String bid = ((JSONObject)obj).getString("@id");
				BNode sobj = null;
				if (bid == null) {
					sobj = graph.getValueFactory().createBNode();
				} else if (bnodeResolver.containsKey(bid)){
					sobj = bnodeResolver.get(bid);
				} else {
					sobj = graph.getValueFactory().createBNode();
					bnodeResolver.put(bid, sobj);
				}
				graph.add(subject, spred, sobj);
				JDILtoGraph((JSONObject)obj, graph, sobj, bnodeResolver);
			} else {
				//Take care of literal as object.
				exportLiteral((JSONObject) obj, graph, subject, spred);
			}
		}
	}

	private void exportLiteral(JSONObject obj, Graph graph, Resource subject, org.openrdf.model.URI predicate) {
		Object objVal= obj.opt("@value");
		Object objLang= obj.opt("@language");
		Object objType= obj.opt("@datatype");

		if(objLang != null) { //value + language
			graph.add(subject, predicate,
					graph.getValueFactory().createLiteral((String)objVal,(String)objLang));
		} else if(objType != null) { //value + type
			graph.add(subject, predicate,
					graph.getValueFactory().createLiteral((String) objVal, 
							new URIImpl(namespaces.expand((String)objType))));
		} else { //value
			graph.add(subject, 
					predicate,
					graph.getValueFactory().createLiteral((String)objVal));
		}

	}


	/**
	 * pseudo code
	 * 
	 *  
	 * @param graph
	 * @param root
	 * @return
	 */
	public JSONObject exportRelationGraphToJSON(Graph graph) {
		JSONObject result = new JSONObject(); 
		//HashMap<String, HashMap<String, ArrayList<String>>> objRelations = new HashMap<String, HashMap<String,ArrayList<String>>>();   
		//HashMap<String, ArrayList<String>> subjRelations = new HashMap<String,ArrayList<String>>();   
		//ArrayList<String> predRelations = new ArrayList<String>();   

		// { Obj1 : { subj1: pred1, ﻿subj2: [pred2, pred4] }
		// 
		// { "res1" : { subj1: pred1, ﻿subj2: [pred2, pred4] },
		for (Statement statement : graph) {
			String obj = namespaces.abbreviate(statement.getObject().stringValue());
			String subj = namespaces.abbreviate(statement.getSubject().stringValue());
			String pred = namespaces.abbreviate(statement.getPredicate().stringValue());

			JSONObject objJSON = null;
			try {
				objJSON = result.getJSONObject(obj);
			} catch (JSONException e) {
				objJSON = new JSONObject();
				try {
					result.put(obj, objJSON);
				} catch (JSONException e1) {
				}
			}

			try {
				objJSON.append(subj, pred);
			} catch (JSONException e1) {

			}
		}

		return result;
	}


	public JSONObject exportGraphToJDIL(Graph graph, Resource root) {
		try {
			HashMap<Resource, JSONObject> res2Jdil= new HashMap<Resource, JSONObject>();
			HashSet<Resource> notRoots = new HashSet<Resource>();

			for (Statement statement : graph) {				
				JSONObject subj = getOrCreateSubject(statement.getSubject(), res2Jdil);   
				String predicate = namespaces.abbreviate(statement.getPredicate().stringValue());
				notRoots.add(statement.getPredicate());
				Value value = statement.getObject();


				if (value instanceof Resource) {
					/*
					 * Create a new JDIL value to accumulate to the subject.
					 */
					JSONObject JDILValueObject = getOrCreateObject((Resource) value,res2Jdil); 

					subj.accumulate(predicate, JDILValueObject);
					notRoots.add((Resource) value);

				} else {
					Literal lit = (Literal) value;
					String language = lit.getLanguage();
					URI datatype = lit.getDatatype();
					JSONObject object = new JSONObject();
					object.accumulate("@value", value.stringValue());
					if (language != null) {
						object.accumulate("@language", language);
					} else if (datatype != null) {
						object.accumulate("@datatype", datatype.stringValue());					
					}
					subj.accumulate(predicate, object);
				}
			}
			if (root != null) {
				JSONObject obj = res2Jdil.get(root);
				cutLoops(obj, new HashSet());
				return obj; 
			}
			HashSet<Resource> roots = new HashSet<Resource>(res2Jdil.keySet());
			roots.removeAll(notRoots);
			if (roots.size() == 1) {
				JSONObject obj = res2Jdil.get(roots.iterator().next());
				cutLoops(obj, new HashSet());
				return obj;
			} 
		} catch (JSONException jse) {
			log.error(jse.getMessage());
		}
		return null;
	}

	void cutLoops(JSONObject obj, HashSet above) {
		if (obj == null) {
			return;
		}
		above.add(obj);
		for (Iterator it = obj.keys();it.hasNext();) {
			String key = (String) it.next();
			Object child = obj.opt(key);
			if (child instanceof JSONObject) {
				if (above.contains(child)) {
					try {
						JSONObject newChild = new JSONObject();
						if (((JSONObject) child).has("@id")) {
							newChild.put("@id", ((JSONObject) child).opt("@id"));
						}
						if (((JSONObject) child).has("@isBlank")) {
							newChild.put("@isBlank", ((JSONObject) child).opt("@isBlank"));
						}
						obj.put(key, newChild);
					} catch (JSONException e) {
					}
				} else {
					cutLoops((JSONObject) child, above);
				}
			} else if (child instanceof JSONArray) {
				JSONArray arr = (JSONArray) child;
				for (int i = 0;i<arr.length(); i++) {
					try {
						Object arrChild = arr.get(i);
						if (arrChild instanceof JSONObject) {
							if (above.contains(arrChild)) {
								JSONObject newChild = new JSONObject();
								if (((JSONObject) arrChild).opt("@id") != null) {
									newChild.put("@id", ((JSONObject) arrChild).opt("@id"));
								} else {
									newChild.put("@bid", ((JSONObject) arrChild).opt("@bid"));
								}
								arr.put(i, newChild);
							} else {
								cutLoops((JSONObject) arrChild, above);
							}
						}
					} catch (JSONException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	JSONObject getOrCreateSubject(Resource resource, Map<Resource, JSONObject> res2Jdil) throws JSONException {
		JSONObject jdil = res2Jdil.get(resource);
		if (jdil == null) {
			jdil = new JSONObject();
			if (resource instanceof BNode) {
				jdil.accumulate("@id", resource.stringValue());
				jdil.accumulate("@isBlank", true);
			} else {
				jdil.accumulate("@id", namespaces.abbreviate(resource.stringValue()));
			}
			res2Jdil.put(resource, jdil);
		}
		return jdil;
	}

	JSONObject getOrCreateObject(Resource resource, Map<Resource, JSONObject> res2Jdil) throws JSONException {
		JSONObject jdil = new JSONObject();
		if (resource instanceof BNode) {
			jdil.accumulate("@id", resource.stringValue());
			jdil.accumulate("@isBlank", true);
		} else {
			jdil.accumulate("@id", namespaces.abbreviate(resource.stringValue()));
		}		
		if (res2Jdil.get(resource) == null) {
			res2Jdil.put(resource, jdil);
		} else {
			jdil = res2Jdil.get(resource); 
		}
		return jdil;
	}

	public Namespaces getNamespaces() {
		return namespaces; 
	}
}