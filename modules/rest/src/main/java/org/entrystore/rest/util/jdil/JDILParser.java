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

package org.entrystore.rest.util.jdil;

import java.io.*;
import java.net.*;
import java.util.*;

import org.json.*;
import org.restlet.*;
import org.restlet.data.*;

/**
 * A class providing a few static methods for parsing org.json datastructures as JDIL.
 * 
 * @author Carl Leonardsson
 *
 */

public class JDILParser {
	
	/**
	 * An interface meant to detect whether or not it is appropriate to interpret string values
	 * in JDIL objects as URIs. Usage: expandJDIL*.
	 * 
	 * @author Carl Leonardsson
	 *
	 */
	public interface URIDetector{
		/**
		 * Detects whether the value of the key <code>key</code> in the JDIL object 
		 * <code>jdil</code> should be interpretted as a URI provided it is a String.
		 * 
		 * @param key a JDIL key value
		 * @param jdil a JDIL object
		 * @return true if the value of the key <code>key</code> in the JDIL object 
		 * <code>jdil</code> should be interpretted as a URI provided it is a String, 
		 * false otherwise
		 */
		Boolean hasURIValue(String key, JSONObject jdil);
	}

	/**
	 * Creates a new namespace by merging <code>namespace</code> and the namespace specified in 
	 * <code>json</code> by jdil notation ("&#64namespaces").
	 * Has no side-effects (save exception throwing).
	 * 
	 * @param json any JSON object
	 * @param namespaces any <code>Map<String,String></code>
	 * @return A copy of <code>namespace</code> updated such that every name/value pair a:b in the
	 * "&#64namespaces" object of <code>json</code> is represented in the returned map by a mapping
	 * a |-&gt b. In cases where there is already a mapping a |-&gt c in <code>namespace</code> c is 
	 * overridden by b. Note that only the "&#64namespaces" value of <code>json</code> is considered -
	 * possible "&#64namespaces" values of child objects of <code>json</code> are ignored.
	 * @throws JDILException if json has a "&#64namespaces" value and that value is faulty according 
	 * to jdil syntax.
	 */
	public static Map<String,String> extractNamespaces(JSONObject json,Map<String,String> namespaces) throws JDILException{
		Map<String,String> newNamespaces = new HashMap<String,String>();
		/* Deep copy namespace into newNamespace */
		Iterator<String> nsIt = namespaces.keySet().iterator();
		while(nsIt.hasNext()){
			String key = nsIt.next();
			newNamespaces.put(key,namespaces.get(key));
		}
		/* Does json have a @namespaces value? */
		if(json.has("@namespaces")){
			Object jsonNamespaces = json.opt("@namespaces");
			if(jsonNamespaces instanceof String){
				/* The string jsonNamespaces is supposed to hold an URI 
				 * which holds the namespaces definitions. (ref. http://jdil.org) 
				 * Read the URI and set jsonNamespaces to a corresponding JSONObject. */
				String nsURI = (String)jsonNamespaces;
				String nsObj;
				try{
					nsObj = getURIContentAsText(nsURI);
				}catch(Exception exc){
					throw new JDILException("Failed to GET namespaces from URI "+nsURI+": "+exc.getMessage());
				}
				try{
					jsonNamespaces = new JSONObject(nsObj);
				}catch(JSONException exc){
					throw new JDILException("Content of URI "+nsURI+" supposed to contain namespace definitions is an invalid JSON object: "+exc.getMessage());
				}
			}
			if(jsonNamespaces instanceof JSONObject){
				Iterator jsonNsIt = ((JSONObject)jsonNamespaces).keys();
				while(jsonNsIt.hasNext()){
					String key = (String)jsonNsIt.next();
					Object o = ((JSONObject)jsonNamespaces).opt(key);
					if(o instanceof java.lang.String){
						newNamespaces.put(key,(String)o);
					}else{
						throw new JDILException("Expected JSON string as value of namespace "+key+", got "+((JSONObject)jsonNamespaces).opt(key).toString());
					}
				}
			}else{
				throw new JDILException("Expected JSON object or URI for @namespaces, got "+jsonNamespaces.toString());				
			}
		}/* else -> it is quite alright not to have a @namespaces value */
		return newNamespaces;
	}
	
	/**
	 * Replaces all JDIL star notations "*"^a:id with a:{"&#64id":id} in <code>json</code>. This is 
	 * carried out not only for key/value pairs in <code>json</code> but also recursively in all objects
	 * contained in <code>json</code>. The replacement is stupid in the aspect that any "*"^a:val will
	 * be replaced by a:{"&#64id":val} regardless of the values or types of a and val.
	 * 
	 * @param json the JDIL object to be rewritten
	 */
	public static void removeJDILStar(JSONObject json){
		Iterator keyIt;
		Set<String> modifiedKeys = new HashSet<String>();
		Boolean noStars = false;
		/* Do replacement for key/value pairs of this object */
		
		while(!noStars){
			noStars = true;
			keyIt = json.keys();
			while(keyIt.hasNext()){
				//System.out.print("key: ");
				String key = (String)keyIt.next();
				//System.out.println(key);
				if((!modifiedKeys.contains(key)) && key.startsWith("*")){
					noStars = false;
					Object id = json.opt(key);
					json.remove(key);
					JSONObject newObj = new JSONObject();
					key = key.substring(1);
					modifiedKeys.add(key);
					try{
						newObj.put("@id",id);
						json.put(key,newObj);
					}catch(JSONException exc){
						/* cannot happen */
						/* Note that "*":val would render "":{"@id":val} 
						 * which actually is acceptable JSON. */
					}
					break;
				}
			}
		}
		/* Recurse */
		keyIt = json.keys();
		while(keyIt.hasNext()){
			String key = (String)keyIt.next();
			Object val = json.opt(key);
			if(val instanceof JSONObject){
				removeJDILStar((JSONObject)val);
			}else if(val instanceof JSONArray){
				removeJDILStar((JSONArray)val);
			}
		}
	}
	
	/**
	 * Replaces all JDIL star notations "*"^a:id with a:{"&#64id":id} in <code>json</code>. This is 
	 * carried recursively for all objects and arrays contained in <code>json</code>. The replacement 
	 * is stupid in the aspect that any "*"^a:val will be replaced by a:{"&#64id":val} regardless of 
	 * the values or types of a and val.
	 * 
	 * @param json the JDIL array to be rewritten
	 */
	public static void removeJDILStar(JSONArray json){
		int i;
		for(i = 0; i < json.length(); i++){
			Object val = json.opt(i);
			if(val instanceof JSONObject){
				removeJDILStar((JSONObject)val);
			}else if(val instanceof JSONArray){
				removeJDILStar((JSONArray)val);
			}
		}
	}
	
	/**
	 * Expands the namespace of <code>uri</code> according to the mapping provided by <code>namespaces</code>. 
	 * 
	 * @param uri any String
	 * @param namespaces any Map<String,String>
	 * @return namespace^id if <code>uri</code> is on the form ns^":"^id and there is a mapping 
	 * ns |-&gt namespace in <code>namespaces</code>, <code>uri</code> otherwise.
	 */
	public static String expandURIString(String uri, Map<String,String> namespaces){
		if(uri.indexOf(":") != -1){
			String shortns = uri.substring(0,uri.indexOf(":"));
			if(namespaces.keySet().contains(shortns)){
				String longns = new String(namespaces.get(shortns));
				return longns.concat(uri.substring(uri.indexOf(":")+1));
			}
		}
		return uri;
	}
	
	/**
	 * Attempts to GET <code>URIString</code> and return the result as text. 
	 * 
	 * @param URIString a String representing an URI with scheme specified 
	 * (e.g. "file://path" rather than just "path")
	 * @return text representing the content of <code>URIString</code>
	 * @throws JDILException if unable to GET content of <code>URIString</code>
	 * @throws IOException if underlying restlet fails (ask restlet documentation for details *haha*)
	 * @throws URISyntaxException if <code>URIString</code> is an URI with invalid syntax
	 */
	private static String getURIContentAsText(String URIString) throws JDILException, IOException, URISyntaxException{
		String scheme = new URI(URIString).getScheme();
		if(scheme == null){
			throw new JDILException("No scheme given in URI "+URIString);
		}else{
			Client client = new Client(new Protocol(scheme));
			Request request = new Request(Method.GET, URIString);
			Response response = client.handle(request);
			if(response.getStatus().equals(Status.SUCCESS_OK) && response.isEntityAvailable()){ /* did the request succeed and yield data? */
				return response.getEntity().getText();
			}else{
				throw new JDILException("Unable to GET URI "+URIString);
			} 
		}
	}

	/**
	 * Creates and returns a copy of <code>jdil</code> normalised with respect to JDIL "&#64id" value and
	 * with every URI value specified with a namespace expanded by the namespace definition. Call the 
	 * returned object <code>jdilCopy</code>. 
	 * <p>In <code>jdilCopy</code> all objects with the same JDIL "&#64id" value are the same (not copies) 
	 * object and that object has every key/value pair occuring in any of the objects in <code>jdil</code> 
	 * with that "&#64id" value. 
	 * <p>Note that this means that loops can be created by certain input JDIL constructs. Such loops will 
	 * not be detected by this method.
	 * <p>In <code>jdilCopy</code> every URI of the form ns^":"^id where a namespace declaration ns:namespace
	 * is visible to the URI is exchanged for namespace^id. Here as URIs are considered precisely the values
	 * (keys are not URIs) val such that the key/value pair key/val is in an object obj which is in 
	 * <code>jdil</code> and uriDetector.hasURIValue(key,obj) is true. Namespace declarations considered are 
	 * those declared in <code>jdil</code> by the JDIL "&#64namespaces"  construct.
	 * <p><code>jdilCopy</code> will contain no JDIL "&#64namespaces" constructs.
	 * <p>This method recurses over JSONObjects and JSONArrays thus not only <code>jdil</code> but also
	 * descendants of <code>jdil</code> will be expanded when creating <code>jdilCopy</code>.
	 * 
	 * @param jdil any JDIL object
	 * @param uriDetector a URIDetector used to determine whether a value is a URI.
	 * @return <code>jdilCopy</code>
	 * @throws JDILException if two objects with the same "&#64id" value has key/value pairs with the same
	 * key but differing values or if an object has a "&#64id" value which is not a string.
	 */
	public static JSONObject expandJDILObject(JSONObject jdil, URIDetector uriDetector) throws JDILException{
		JSONObject jdilCopy = null; /* initialisation avoids complaints, see below */
		try{
			jdilCopy = new JSONObject(jdil.toString());
		}catch(JSONException exc){ /* cannot happen */ }
		removeJDILStar(jdilCopy);
		return expandJDILObjectWithoutStar(jdilCopy, new HashMap<String,String>(), new HashMap<String,JSONObject>(), uriDetector);
	}

	/**
	 * Maps expandJDILObject over all JSONObjects and JSONArrays in <code>jdil</code>. 
	 * 
	 * @param jdil any JDIL array
	 * @param uriDetector see expandJDILObject
	 * @return a deep copy of <code>jdil</code> where expandJDILObject has been mapped over all JDILObjects
	 * in <code>jdil</code>
	 * @throws JDILException if any of the calls to expandJDILObject does
	 */
	public static JSONArray expandJDILArray(JSONArray jdil, URIDetector uriDetector) throws JDILException{
		JSONArray jdilCopy = null; /* initialisation avoids complaints, see below */
		try{
			jdilCopy = new JSONArray(jdil.toString());
		}catch(JSONException exc){ /* cannot happen */ }
		removeJDILStar(jdilCopy);
		return expandJDILArrayWithoutStar(jdilCopy,new HashMap<String,String>(),new HashMap<String,JSONObject>(),uriDetector);
	}

	/**
	 * Creates and returns a copy of <code>jdil</code> normalised with respect to JDIL "&#64id" value and
	 * with every URI value specified with a namespace expanded by the namespace definition. Call the 
	 * returned object <code>jdilCopy</code>. 
	 * <p>In <code>jdilCopy</code> all objects with the same JDIL "&#64id" value are the same (not copies) 
	 * object and that object has every key/value pair occuring in any of the objects in <code>jdil</code> 
	 * with that "&#64id" value. If there is a JDIL object in <code>namedJDILObjects</code> with the same
	 * "&#64id" value then the objects in <code>jdilCopy</code> with that "&#64id" value will be the same
	 * object as that object. After this method returns all objects in <code>jdilCopy</code> with a "&#64id"
	 * value will be represented in <code>namedJdilObjects</code>, the ones in <code>namedJdilObjects</code>
	 * from the beginning will remain there but might be extended with additional key/value pairs as 
	 * described above. 
	 * <p>Note that this means that loops can be created by certain input JDIL constructs. Such loops will 
	 * not be detected by this method.
	 * <p>In <code>jdilCopy</code> every URI of the form ns^":"^id where a namespace declaration ns:namespace
	 * is visible to the URI is exchanged for namespace^id. Here as URIs are considered precisely the values
	 * (keys are not URIs) val such that the key/value pair key/val is in an object obj which is in 
	 * <code>jdil</code> and uriDetector(key,obj) is true. Namespace declarations considered are those in
	 * <code>namespaces</code> and those declared in <code>jdil</code> by the JDIL "&#64namespaces" 
	 * construct.
	 * <p><code>jdilCopy</code> will contain no JDIL "&#64namespaces" constructs.
	 * <p>This method recurses over JSONObjects and JSONArrays thus not only <code>jdil</code> but also
	 * descendants of <code>jdil</code> will be expanded when creating <code>jdilCopy</code>.
	 * 
	 * @param jdil any JDIL object in which there is no occurence of the JDIL "*" construct.
	 * @param namespaces a mapping where every a |-&gt b is regarded as a namespace declaration a:b 
	 * outside of but visible to <code>jdil</code>. This map should probably be empty in most cases.
	 * @param namedJdilObjects a mapping from JDIL "&#64id" values to JDIL objects with those JDIL 
	 * "&#64id" values. This map should probably be empty in most cases.
	 * @param uriDetector a URIDetector used to determine whether a value is a URI.
	 * @return <code>jdilCopy</code>
	 * @throws JDILException if two objects with the same "&#64id" value has key/value pairs with the same
	 * key but differing values or if an object has a "&#64id" value which is not a string.
	 */	
	private static JSONObject expandJDILObjectWithoutStar(JSONObject jdil, Map<String,String> namespaces, Map<String,JSONObject> namedJdilObjects, URIDetector uriDetector) throws JDILException{
		JSONObject jdilCopy;
		Map<String,String> newNamespaces = extractNamespaces(jdil,namespaces);
		try{
			jdilCopy = new JSONObject(jdil.toString()); /* copy jdil */
		}catch(JSONException exc){
			/* cannot happen */
			jdilCopy = new JSONObject(); /* avoids complaints */
		}
		/* URI expansion and recursive calls */
		Iterator keyIt = jdilCopy.keys();
		while(keyIt.hasNext()){
			String key = (String)keyIt.next();
			Object val = jdilCopy.opt(key); /* safe since we know that jdilCopy.has(key) */
			
			try{
				if(val instanceof JSONObject){
					/* Recurse */
					jdilCopy.put(key, expandJDILObjectWithoutStar((JSONObject)val,newNamespaces,namedJdilObjects,uriDetector));
				}else if(val instanceof JSONArray){
					/* Recurse */
					jdilCopy.put(key, expandJDILArrayWithoutStar((JSONArray)val,newNamespaces,namedJdilObjects,uriDetector));
				}else if(val instanceof String && 
						  uriDetector.hasURIValue(key,jdilCopy)){
					/* Expand URI */
					jdilCopy.put(key,expandURIString((String)val,newNamespaces));
				}
			}catch(JSONException exc){ /* cannot happen */ }
		}
		if(jdilCopy.has("@id")){
			/* "@id" namespace */
			Object id = jdilCopy.opt("@id"); /* safe since we know that sirffCopy.has("@id")*/
			if(id instanceof java.lang.String){
				try{
					jdilCopy.put("@id",expandURIString((String)id,newNamespaces));
				}catch(JSONException exc){
					/* cannot happen */
				}
				/* "@id" value collision? */
				if(namedJdilObjects.keySet().contains(jdilCopy.opt("@id"))){
					/* collision */
					/* replace sirffCopy by the other object with the same id */
					JSONObject tmp = jdilCopy;
					jdilCopy = namedJdilObjects.get(jdilCopy.opt("@id"));
					/* merge key/value pairs */
					keyIt = tmp.keys();
					while(keyIt.hasNext()){
						String key = (String)keyIt.next();
						if(jdilCopy.has(key)){
							/* possible to merge? */
							if(!jdilCopy.opt(key).equals(tmp.opt(key))){
								throw new JDILException("Colliding definitions of "+key+" value in object "+(String)id+". Both "+jdilCopy.opt(key).toString()+" and "+tmp.opt(key)+".");
							}
						}else{
							try{
								jdilCopy.put(key,tmp.get(key));
							}catch(JSONException exc){ /* cannot happen */ }
						}
					}
				}else{
					/* insert a new mapping to namedJdilObjects */
					namedJdilObjects.put(jdilCopy.opt("@id").toString(),jdilCopy);
				}
			}else{
				throw new JDILException("Expected URI for @id, got "+id.toString());
			}
		}
		jdilCopy.remove("@namespaces");
		return jdilCopy;
	}

	/**
	 * Maps expandJDILObjectWithoutStar over all JSONObjects and JSONArrays in <code>jdil</code>. 
	 * 
	 * @param jdil any JDIL array which does not contain any occurence of the JDIL "*" construct.
	 * @param namespaces see expandJDILObjectWithoutStar
	 * @param namedJdilObjects see expandJDILObjectWithoutStar
	 * @param uriDetector see expandJDILObjectWithoutStar
	 * @return a deep copy of <code>jdil</code> where expandJDILObjectWithoutStar has been mapped over 
	 * all JDILObjects in <code>jdil</code>
	 * @throws JDILException if any of the calls to expandJDILObject does
	 */
	private static JSONArray expandJDILArrayWithoutStar(JSONArray jdil, Map<String,String> namespaces, Map<String,JSONObject> namedJdilObjects, URIDetector uriDetector) throws JDILException{
		JSONArray copy = new JSONArray();
		int i;
		for(i = 0; i < jdil.length(); i++){
			try{
				Object obj = jdil.get(i);
				if(obj instanceof JSONObject){
					copy.put(i,expandJDILObjectWithoutStar((JSONObject)obj,namespaces,namedJdilObjects,uriDetector));
				}else if(obj instanceof JSONArray){
					copy.put(i,expandJDILArrayWithoutStar((JSONArray)obj,namespaces,namedJdilObjects,uriDetector));
				}else{
					copy.put(i,obj);
				}
			}catch(JSONException exc){ /* cannot happen */ }
		}
		return copy;
	}
	
	/**
	 * Checks whether a JDIL object <code>jdil</code> contains loops.
	 * 
	 * @param jdil any JSONObject which is expanded in the way described in expandJDILObject.
	 * @return true if any object in <code>jdil</code> has a descendant object with the same "&#64id"
	 * value as itself, false otherwise.
	 */
	public static Boolean hasLoops(JSONObject jdil){
		return hasLoops(jdil, new HashSet<String>());
	}
	
	/**
	 * Checks whether a JDIL object <code>jdil</code> contains loops or any "&#64id" value which also
	 * occurs in <code>ancestors</code>.
	 * 
	 * @param jdil any JSONObject which is expanded in the way described in expandJDILObject
	 * @param ancestors any string set
	 * @return true if any object in <code>jdil</code> has a descendant object with the same "&#64id"
	 * value as itself or if any object in <code>jdil</code> has an "&#64id" value which occurs in 
	 * <code>ancestors</code>, false otherwise.
	 */
	private static Boolean hasLoops(JSONObject jdil, HashSet<String>ancestors){
		Object tmpObj = null;
		String id = null;
		Boolean hasLoop = false;
		if(jdil.has("@id")){
			tmpObj = jdil.opt("@id");
			if(tmpObj instanceof String){
				id = (String)tmpObj;
				if(ancestors.contains(id)){
					return true;
				}else{
					ancestors.add(id);
				}
			}
		}
		/* Recurse */
		Iterator keyIt = jdil.keys();
		while(keyIt.hasNext()){
			String key = (String)keyIt.next();
			tmpObj = jdil.opt(key);
			if(tmpObj instanceof JSONObject){
				if(hasLoops((JSONObject)tmpObj,ancestors)){
					hasLoop = true;
					break;
				}
			}else if(tmpObj instanceof JSONArray){
				if(hasLoops((JSONArray)tmpObj,ancestors)){
					hasLoop = true;
					break;
				}
			}
		}
		/* If we added the @id of this object - remove it now */
		/* We know that this @id was not in ancestors before so we can safely remove it */
		if(id != null){
			ancestors.remove(id);
		}
		return hasLoop;
	}
	
	/**
	 * Checks whether a JDIL array <code>jdil</code> contains loops or any "&#64id" value which also
	 * occurs in <code>ancestors</code>.
	 * 
	 * @param jdil any JSONArray which is expanded in the way described in expandJDILArray
	 * @param ancestors any string set
	 * @return true if any object in <code>jdil</code> has a descendant object with the same "&#64id"
	 * value as itself or if any object in <code>jdil</code> has an "&#64id" value which occurs in 
	 * <code>ancestors</code>, false otherwise.
	 */
	private static Boolean hasLoops(JSONArray jdil, HashSet<String> ancestors){
		Boolean hasLoop = false;
		for(int i = 0; i < jdil.length(); i++){
			Object tmpObj = jdil.opt(i);
			if(tmpObj instanceof JSONObject){
				if(hasLoops((JSONObject)tmpObj,ancestors)){
					hasLoop = true;
					break;
				}
			}else if(tmpObj instanceof JSONArray){
				if(hasLoops((JSONArray)tmpObj,ancestors)){
					hasLoop = true;
					break;
				}
			}
		}
		return hasLoop;
	}
}