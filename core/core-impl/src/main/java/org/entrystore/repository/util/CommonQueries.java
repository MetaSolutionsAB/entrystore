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

package org.entrystore.repository.util;

import org.entrystore.repository.ContextManager;
import org.entrystore.repository.Entry;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.repository.RepositoryException;


public class CommonQueries {
	/**
	 * Creates a SPARQL quary to the IOA-PHM target to the method listIdentifiers()
	 *
	 * Formats on the dates must be YYYY-MM-DD or YYYY-MM-DDTHH:MM:SSZ
	 * <pre>
	 * Example:
	 * String query = CommonQueries.createListIdentifiersQuery("2008-01-01","2008-02-02T00:00:00Z","context:1", {ContextManager}); 
	 * </pre>
	 * @param from beginning date using the proper granularity.
	 * @param until ending date using the proper granularity.
	 * @param set the set name or null if no such limit is requested. 
	 * formats on the set must be context, context:{context-id} or null.
	 * @return a quary which we can search with
	 */
	public static String createListIdentifiersQuery(String from, String until, String set, ContextManager cm) throws RepositoryException, MalformedQueryException {	
		
		// BEGIN: Fix the format on the dates.
		if(from.lastIndexOf("Z") == -1) {
			from += "T00:00:00Z\"^^xsd:dateTime";  
		} else {
			from += "\"^^xsd:dateTime"; 			
		}
		from = "\"" + from.toString(); 

		if(until.lastIndexOf("Z") == -1) {
			until +="T23:59:59Z\"^^xsd:dateTime"; 
		} else {
			until+="\"^^xsd:dateTime"; 			
		}
		until = "\"" + until.toString();
		// END
		
		
		// Create the query string.
		String queryString = null; 
		int i = 0; 
		if(set == null || set.toLowerCase().equals("context")) {
			// If no context is specified.
			queryString = new String("PREFIX dc:<http://purl.org/dc/terms/> "+
					"PREFIX xsd:<http://www.w3.org/2001/XMLSchema#> "+
					"SELECT ?entryUri "+
					"WHERE  { ?entryUri dc:created ?createdDate ."+
					"?entryUri dc:modified ?modifiedDate ."+
					"FILTER(?createdDate >= "+from+" &&"
					+"?createdDate <= "+until+" &&"+
					"?modifiedDate >= "+from+" &&"+
					"?modifiedDate <= "+until+")"+
					"}"); 
		} else if(0!=(i = set.lastIndexOf(":"))) {
			String contextNr = set.substring(i+1);  
			try {
				Integer.valueOf(contextNr).intValue(); 
			} catch (NumberFormatException e) {
				return null; 
			}

			Entry e = cm.get(contextNr); 
			if(e == null) 
				return null; 

			// If a context is specified.
			queryString = new String("PREFIX dc:<http://purl.org/dc/terms/> "+
					"PREFIX xsd:<http://www.w3.org/2001/XMLSchema#> "+
					"SELECT ?entryUri "+
					"FROM <"+e.getEntryURI().toString()+"> "+
					"WHERE  { ?entryUri dc:created ?createdDate ."+
					"?entryUri dc:modified ?modifiedDate ."+
					"FILTER(?createdDate >= "+from+" &&"
					+"?createdDate <= "+until+" &&"+
					"?modifiedDate >= "+from+" &&"+
					"?modifiedDate <= "+until+") }"); 

		} else {
			return null; // The set is goofy 
		}
		return queryString; 
	}
}
